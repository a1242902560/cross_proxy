import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author : chendm3
 * @since : 2022/8/24 16:40
 */
public class BioClient_LAZY {

    private static final String 公网服务器地址 = "localhost";
    private static final String 内网服务器地址 = "124.222.84.105";
    // private static final String 内网服务器地址 = "198.18.0.43";//sit
    //    private static final String 内网服务器地址 = "152.32.206.114";//qingcloud

    private static final Integer 公网心跳端口 = 8333;
    private static final Integer 内网服务器端口 = 8764;

    private static class Env {
        private static final int 内网服务端最长空闲时间 = 720_000;
        private static final int 公网服务端最长空闲时间 = 3600_000;

        private static final int 默认的客户端代理_增加数量 = 1;
        private static final int 默认的客户端代理数量 = 1;
        private static final int 公网服务端最短心跳时间 = 10_000;

        private static final String 字符串分隔符 = ";";

    }


    private static Map<Socket, Socket> 公网_内网_SOCKET_映射 = new HashMap<>();

    private static volatile long 心跳时间 = System.nanoTime();

    private static volatile ThreadPoolExecutor 本地代理线程池 = null;

    private static Object LOCK = new Object();
    private static final AtomicInteger 本地代理线程编号 = new AtomicInteger(0);


    public static void main(String[] args) {
        启动内网代理服务器();
    }

    private static void 启动内网代理服务器() {
        System.out.println("=============================================================================================================");
        System.out.println("*****************************************  内网服务启动 ===>>> ");
        System.out.println("=============================================================================================================");
        初始化线程池资源();
        loop创建代理服务器池资源();
        连接心跳服务();
        System.out.println("=========================================  内网服务启动 成功 ===================");
    }

    private static void 连接心跳服务() {
        new Thread(BioClient_LAZY::进行心跳连接, "心跳处理-基础线程").start();
    }

    private static void 进行心跳连接() {
        Socket socket = null;
        try {
            socket = new Socket(公网服务器地址, 公网心跳端口);
            接收公网服务器指令(socket);

            while (true) {
                OutputStream outputStream = socket.getOutputStream();
                outputStream.write("beat".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                sleep(1000);
                if (getTimeCostMs(心跳时间) > Env.公网服务端最短心跳时间) {
                    System.err.println("远程代理服务器宕机，尝试重新连接服务端local:" + socket.getLocalSocketAddress() + " 离线时间:" + getTimeCostMs(心跳时间) + "ms");
                    sleep(600);
                    关闭socket(socket);
                    进行心跳连接();
                }
            }
        } catch (Exception e) {
            System.err.println("心跳失败:" + e.getMessage());
            sleep(600);
            关闭socket(socket);
            进行心跳连接();
        }
    }

    private static void 关闭socket(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception e) {
            System.err.println("关闭心跳失败:" + e.getMessage());
        }
    }

    private static Thread 接收公网服务器指令(Socket socket) {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    InputStream inputStream = socket.getInputStream();
                    int available = inputStream.available();
                    if (available <= 0) {
                        sleep(80);
                        continue;
                    }

                    byte[] bytes = new byte[available];
                    int read = inputStream.read(bytes);
                    String 指令 = new String(bytes, StandardCharsets.UTF_8);
                    if (!指令.isEmpty() && ThreadLocalRandom.current().nextInt(20) == 1) {
                        System.out.println("接收到公网服务端指令:" + 指令);
                    }
                    Arrays.stream(指令.split(Env.字符串分隔符)).distinct()
                            .forEach(BioClient_LAZY::处理服务端指令);

                } catch (Exception e) {
                    System.err.println("心跳指令线程崩溃:" + e.getMessage());
                    throw new RuntimeException(e);
                }
            }

        }, "接收公网服务器指令线程" + ThreadLocalRandom.current().nextInt(1024));
        thread.start();
        return thread;
    }

    private static void 处理服务端指令(String 指令) {
        switch (指令) {
            case "beat":
                心跳时间 = System.nanoTime();
                break;
            case "addThread":
                System.err.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> 新增连接:");
                System.err.println("本地客户端代理新增连接:激活数量" + 本地代理线程池.getActiveCount() + " 最大线程数量:" + 本地代理线程池.getMaximumPoolSize());
                System.err.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> 新增连接:");
                synchronized (LOCK) {
                    if (本地代理线程池.getMaximumPoolSize() - 本地代理线程池.getActiveCount() <= 3) {
                        本地代理线程池.setMaximumPoolSize(Math.min(本地代理线程池.getMaximumPoolSize() + Env.默认的客户端代理_增加数量, 512));
                    }
                }
                创建代理服务器资源(); //主动提交一次代理创建
                break;
            default:
                System.err.println("未知的服务端指令:" + 指令);
                break;
        }
    }

    private static void loop创建代理服务器池资源() {

  /*      new Thread(() -> {
            System.out.println("默认不启动代理客户端，服务端访问后再启动");
            while (true) {
                try {
                    创建代理服务器资源();
                } finally {
                    sleep(200 + ThreadLocalRandom.current().nextInt(2000));
                }
            }
        }, "内网代理服务器核心LOOP").start();*/

    }

    private static void 初始化线程池资源() {
        if (本地代理线程池 != null) {
            return;
        }


        synchronized (LOCK) {
            if (本地代理线程池 != null) {
                return;
            }

            final AtomicInteger no = new AtomicInteger(0);
            本地代理线程池 = new ThreadPoolExecutor(Env.默认的客户端代理数量, Env.默认的客户端代理数量, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread thread = new Thread(r);
                thread.setName("本地客户端代理:" + no.incrementAndGet());
                return thread;
            }, (r, executor) -> {
                if (ThreadLocalRandom.current().nextInt(50) == 1) {
                    System.out.println("丢弃多余的任务");
                }
            });
        }
    }

    private static void 创建代理服务器资源() {
        try {
            本地代理线程池.execute(BioClient_LAZY::连接远程代理服务器);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 创建可被代理的
     *
     * @throws Exception
     */

    private static void 连接远程代理服务器() {
        int no = 本地代理线程编号.incrementAndGet();
        //     while (true) {
        try {
            System.out.println("线程:【" + Thread.currentThread().getName() + "】===>>> 进入远程连接重新创建:" + no + "线程池 激活:" + 本地代理线程池.getActiveCount() + " 最大:" + 本地代理线程池.getMaximumPoolSize());
            执行远程代理连接(no);
        } catch (Exception e) {
            System.err.println("爱岗啥 公网服务器连接失败:" + e.getMessage());
        } finally {
            System.out.println("==================================== 休眠等待重新连接 线程:" + Thread.currentThread().getName());
            sleep(6666 + new Random().nextInt(no * 60));
            System.out.println("==================================== 休眠结束重新连接 线程:" + Thread.currentThread().getName());
        }
        //      }
        // socket.close();
    }

    private static void 执行远程代理连接(int no) throws IOException {
        Socket socket = new Socket(公网服务器地址, 8000);
        try {
            long start = System.nanoTime();
            while (true) {

                InputStream inputStream = socket.getInputStream();

                byte[] bytes = new byte[inputStream.available()];
                final int read = inputStream.read(bytes);
                OutputStream 对内网服务器流 = 选取内网连接服务(socket, no);
                if (对内网服务器流 != null && read > 0) {
                    对内网服务器流.write(bytes);
                    对内网服务器流.flush();
                }
                if (read > 0) {
                    System.out.println("【" + no + "】接收到 公网服务端[local:" + socket.getLocalAddress() + " remote:" + socket.getRemoteSocketAddress() + "] 的数据：length:" + read + "  content:" + new String(bytes, 0, read));
                    start = System.nanoTime();
                } else {
                    sleep(100);
                }

                if (getTimeCostMs(start) > Env.公网服务端最长空闲时间) {
                    System.err.println("公网代理服务 空闲超时，关闭公网代理连接，关闭客户端代理连接");
                    throw new RemoteException("抛出异常关闭");
                }

            }
        } catch (Exception e) {
            if (!Optional.ofNullable(e.getMessage()).orElse("").contains("抛出异常关闭")) {
                System.out.println("执行远程代理连接 处理异常");
                e.printStackTrace();
                System.out.println("执行远程代理连接 处理异常结束");
            }
        } finally {
            关闭对内和对外流(socket);
        }


    }

    private static OutputStream 选取内网连接服务(Socket socket, int no) throws IOException {
        Socket originSocketInternal = 公网_内网_SOCKET_映射.get(socket);
        if (Objects.nonNull(originSocketInternal)) {
            return originSocketInternal.getOutputStream();
        }
        启动内网目标服务处理线程(socket, socket.getOutputStream(), no);
        originSocketInternal = 公网_内网_SOCKET_映射.get(socket);
        if (Objects.nonNull(originSocketInternal)) {
            return originSocketInternal.getOutputStream();
        }
        throw new RemoteException("系统异常，内网连接失败");
    }

    private static void 启动内网目标服务处理线程(Socket socket, OutputStream 代理本地目标服务_和对公网代理服务器连对接, int no) {
        getSocketInternal(socket);
        new Thread(() -> {
            long start = System.nanoTime();


            while (true) {
                try {

                    Socket socketInternal = 公网_内网_SOCKET_映射.get(socket);
                    if (Objects.isNull(socketInternal)) {
                        System.out.println("公网_内网_SOCKET_映射 丢失，大概率是流被关闭了:local:" + socket.getLocalSocketAddress() + " remote:" + socket.getRemoteSocketAddress());
                        break;
                    }

                    InputStream inputStream = socketInternal.getInputStream();
                    int available = inputStream.available();
                    byte[] bytes = new byte[available];
                    inputStream.read(bytes);
                    if (代理本地目标服务_和对公网代理服务器连对接 != null && available > 0) {
                        代理本地目标服务_和对公网代理服务器连对接.write(bytes);
                        代理本地目标服务_和对公网代理服务器连对接.flush();
                    }
                    if (available > 0) {
                        start = System.nanoTime();
                        System.out.println("【" + no + "】接收到 内网服务端[local:" + socketInternal.getLocalSocketAddress()
                                + " remote:" + socketInternal.getRemoteSocketAddress() + "] 的数据：" + available + "  " + new String(bytes, 0, available));
                    }else {
                        sleep(100);
                    }

                    if (getTimeCostMs(start) > Env.内网服务端最长空闲时间) {
                        System.err.println("内网代理服务 空闲超时，关闭内网代理连接，关闭客户端代理连接");
                        throw new RuntimeException("抛出异常关闭 对内网服务器流");
                    }

                } catch (Exception e) {
                    if (!Optional.ofNullable(e.getMessage()).orElse("").contains("抛出异常关闭")) {
                        System.out.println("【" + no + "】读内网通知公网 处理异常");
                        e.printStackTrace();
                        System.out.println("【" + no + "】读内网通知公网 处理异常结束");
                    }
                    //    getSocketInternal(socket);
                    关闭对内和对外流(socket);
                    break;
                }
            }
        }, "内网目标服务器访问线程:" + no).start();
    }

    private static long getTimeCostMs(long start) {
        return (System.nanoTime() - start) / 1000_000;
    }

    private static void 关闭对内和对外流(Socket socket) {
        try {

            Socket remove = 公网_内网_SOCKET_映射.remove(socket);
            if (Objects.nonNull(remove)) {
                remove.close();
                System.out.println("关闭【内网】流:local" + remove.getLocalSocketAddress() + " remote:" + remove.getRemoteSocketAddress());

            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("流关闭异常 2");
        }


        try {
            System.out.println("关闭【公网】流:local" + socket.getLocalSocketAddress() + " remote:" + socket.getRemoteSocketAddress());
        } catch (Exception e) {
        }
        try {
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("流关闭异常 1");
        }

    }

    private static Socket getSocketInternal(Socket socket) {
        try {
            Socket socketInternal = new Socket(内网服务器地址, 内网服务器端口);
            公网_内网_SOCKET_映射.put(socket, socketInternal);
            return socketInternal;
        } catch (Exception e) {
            System.out.println("目标内网服务端【连接】 处理异常");
            e.printStackTrace();
            System.out.println("目标内网服务端【连接】 处理异常结束");
        }
        return null;
    }


    private static void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
