import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class NioClient_LAZY {


    // private static final String 公网服务器地址 = "124.222.84.105";
    private static final String 公网服务器地址 = "localhost";
    private static final Integer 公网服务器端口 = 8001;
    private static final Integer 公网心跳端口 = 8331;
    // private static final String 内网服务器地址 = "124.222.84.105";
    // private static final String 内网服务器地址 = "198.18.0.70";
    //  private static final String 内网服务器地址 = "192.168.103.217";
    //  private static final String 内网服务器地址 = "127.0.0.1";//
    // private static final String 内网服务器地址 = "apigw-sit.ypshengxian.com";//sit中台网关
   // private static final String 内网服务器地址 = "198.18.0.43";//sit中台网关
    private static final String 内网服务器地址 = "180.101.50.242";//资源服务器
    //  private static final String 内网服务器地址 = "198.18.0.70";

    //    private static final String 内网服务器地址 = "152.32.206.114";//qingcloud
    // private static final Integer 内网服务器端口 = 18081;
    private static final Integer 内网服务器端口 = 443;

    private static class Env {

        private static final int 通知超时时间 = 3_000;
        private static final int 内网服务端最长空闲时间 = 720_000;
        private static final int 公网服务端最长空闲时间 = 3600_000;

        private static final int 默认的客户端代理_增加数量 = 1;
        private static final int 默认的客户端代理数量 = 2;
        private static final int 内网客户端最大代理数 = 1024;
        private static final int 公网服务端最短心跳时间 = 100_000;

        private static final String 字符串分隔符 = ";";

        private static final int 缓冲区大小 = 8048;


    }

    private static Map<SocketChannel, SocketChannel> 公网_内网_SOCKET_映射 = new HashMap<>();

    private static volatile long 心跳时间 = System.nanoTime();
    private static final AtomicInteger 公网代理线程池编号 = new AtomicInteger(0);
    private static final AtomicInteger 内网代理线程池编号 = new AtomicInteger(0);
    private static final AtomicInteger 本地代理线程编号 = new AtomicInteger(0);
    private static final AtomicInteger 心跳线程编号 = new AtomicInteger(0);
    private static final AtomicInteger 心跳基础线程编号 = new AtomicInteger(0);

    private static final ThreadPoolExecutor 指令处理线程池 = new ThreadPoolExecutor(2, 2, 3, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread thread = new Thread(r);
        thread.setName("指令处理线程:" + ThreadLocalRandom.current().nextInt(1024));
        return thread;
    }, (r, executor) -> {
        if (ThreadLocalRandom.current().nextInt(50) == 1) {
            System.out.println("丢弃多余的指令任务");
        }
    });

    private static final ThreadPoolExecutor 公网代理线程池 = new ThreadPoolExecutor(Env.默认的客户端代理数量, Env.内网客户端最大代理数, 3, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread thread = new Thread(r);
        thread.setName("本地客户端代理:" + 公网代理线程池编号.incrementAndGet());
        return thread;
    }, (r, executor) -> {
        if (ThreadLocalRandom.current().nextInt(50) == 1) {
            System.out.println("丢弃多余的任务");
        }
    });
    private static final ThreadPoolExecutor 内网目标服务器访问线程 = new ThreadPoolExecutor(Env.默认的客户端代理数量, Env.内网客户端最大代理数, 3, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread thread = new Thread(r);
        thread.setName("内网目标服务器访问线程:" + 内网代理线程池编号.incrementAndGet());
        return thread;
    }, (r, executor) -> {
        if (ThreadLocalRandom.current().nextInt(50) == 1) {
            System.out.println("丢弃多余的[内网目标服务器访问线程]任务");
        }
    });

    private static final ArrayBlockingQueue<ToBeSendMsg> 待推送的消息队列 = new ArrayBlockingQueue<>(1024);

    private static Object LOCK = new Object();


    private static List<Object> 心跳服务任务队列 = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        预热线程();
        内网消息推公网发送线程();
        连接心跳服务();
    }

    /**
     * 对待处理的消息，按照通道分组，或者hash到不同的线程中推送
     */
    private static void 内网消息推公网发送线程() {
        new Thread(() -> {
            while (true) {
                try {
                    ToBeSendMsg take = 待推送的消息队列.take();
                    if (take.socketChannel.isConnected()) {
                        take.socketChannel.write(take.buffer);
                    } else {
                        System.out.println("内网通道已经关闭:消息不在推送:" + take.socketChannel);
                    }

                } catch (Exception e) {
                    System.err.println("消息推送处理失败:" +" 消息长度:"+待推送的消息队列.size()+" err:"+ e.getMessage());
                    sleep(10);
                }
            }
        },"内网消息推公网发送线程").start();
    }


    private static void 预热线程() {
        Runnable r = () -> System.out.println("线程预热: "+Thread.currentThread().getName());
        for (int i = 0; i < 12; i++) {
            try {
                公网代理线程池.submit(r).get();
                指令处理线程池.submit(r).get();
                内网目标服务器访问线程.submit(r).get();
            } catch (Exception e) {
                System.err.println("预热线程fail:" + e.getMessage());
            }
        }
    }


    private static void 连接心跳服务() {
        new Thread(() -> {
            while (true) {
                try {
                    synchronized (心跳服务任务队列) {
                        if (!心跳服务任务队列.isEmpty()) {
                            Thread thread = new Thread(() -> 进行心跳连接(), "心跳-基础线程：" + 心跳基础线程编号.incrementAndGet());
                            thread.start();
                            心跳服务任务队列.clear();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("心跳服务构建失败:" + e.getMessage());
                } finally {
                    sleep(2000);
                }

            }
        }, "心跳守护线程【心跳基础线程 崩溃恢复线程】").start();

        retry连接心跳服务();
    }

    private static void 进行心跳连接() {
        SocketChannel socketChannel = null;
        Thread 心跳推送线程 = null;
        Thread 指令处理线程 = null;
        try {
            指令处理线程 = Thread.currentThread();

            Selector selector = Selector.open();
            socketChannel = getSocketChannel(公网服务器地址, 公网心跳端口, selector);

            AtomicReference<Boolean> 心跳线程状态 = new AtomicReference<>(true);


            while (true) {
                if (!心跳线程状态.get()) {
                    throw new RuntimeException("心跳推送崩溃");
                }
                selector.select(Env.通知超时时间);
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while ((keyIterator.hasNext())) {
                    SelectionKey key = keyIterator.next();
                    removeSelectKey(keyIterator);

                    if (key.isConnectable()) {
                        handleConnectEvent(key);
                        System.out.println("【公网 心跳】服务器连接成功");
                        心跳推送线程 = 发送心跳(socketChannel, (SocketChannel) key.channel(), 心跳线程状态, 指令处理线程);
                    } else if (key.isReadable()) {
                        接收公网服务器指令(key);
                    } else {
                        System.err.println("未知的公网服务key状态：" + key);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("心跳失败:" + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("心跳基础【" + Optional.ofNullable(指令处理线程).map(Thread::getName).orElse("未知线程") + "】线程退出");
            retry连接心跳服务(socketChannel);
            stopThread(心跳推送线程);
        }

    }

    private static void stopThread(Thread 心跳推送线程) {
        try {
            Optional.ofNullable(心跳推送线程).ifPresent(Thread::interrupt);
        } catch (Exception e) {
            try {
                System.err.println("终止心跳线程" + 心跳推送线程.getName() + "失败:" + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private static Thread 发送心跳(SocketChannel remoteChannel, SocketChannel channel, AtomicReference<Boolean> state, Thread 指令处理线程) {
        Thread thread = new Thread(() -> {
            try {
                while (true) {
                    ByteBuffer buffer = ByteBuffer.wrap(encodeMsg("beat").getBytes(StandardCharsets.UTF_8));
                    channel.write(buffer);
                    // System.out.println("给心跳服务端推送心跳:"+channel);
                    sleep(1000);
                    if (getTimeCostMs(心跳时间) > Env.公网服务端最短心跳时间) {
                        System.err.println("远程代理服务器宕机，尝试重新连接服务端local:" + channel.getLocalAddress() + " 离线时间:" + getTimeCostMs(心跳时间) + "ms");
                        throw new RuntimeException("心跳超时");
                    }
                }
            } catch (Exception e) {
                System.err.println("心跳推送失败:" + e.getMessage());
                //  关闭socket(remoteChannel);
                state.getAndSet(false);
                stopThread(指令处理线程);
            }

        }, "心跳推送线程:" + 心跳线程编号.incrementAndGet());
        thread.start();
        return thread;
    }

    private static String encodeMsg(String msg) {
        return msg + Env.字符串分隔符;
    }

    private static void retry连接心跳服务(SocketChannel channel) {
        关闭socket(channel);
        心跳服务任务队列.add("task");
    }

    private static void retry连接心跳服务() {
        retry连接心跳服务(null);
    }

    private static void 关闭socket(SocketChannel socketChannel) {
        try {
            if (socketChannel != null) {
                socketChannel.close();
            }
        } catch (Exception e) {
            System.err.println("关闭心跳失败:" + e.getMessage());
        }
    }

    private static long getTimeCostMs(long start) {
        return (System.nanoTime() - start) / 1000_000;
    }

    private static void 接收公网服务器指令(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(Env.缓冲区大小);
        int bytesRead = socketChannel.read(buffer);

        if (bytesRead > 0) {
            buffer.flip();
            byte[] data = new byte[bytesRead];
            buffer.get(data);

            String 指令 = new String(data, StandardCharsets.UTF_8);
            if (!指令.isEmpty() && ThreadLocalRandom.current().nextInt(120) == 1) {
                System.out.println("接收到公网服务端指令:" + 指令 + " 上一次心跳时间间隔:" + (getTimeCostMs(心跳时间) / 1000) + "秒");
            }
            Arrays.stream(指令.split(Env.字符串分隔符)).distinct().forEach(NioClient_LAZY::处理服务端指令);
        } else if (bytesRead == -1) {
            throw new RemoteException("心跳线程被服务端关闭");
        } else {
            System.out.println("接收公网服务器指令：空消息");
        }
    }


    private static void 处理服务端指令(String 指令) {
        指令处理线程池.execute(() -> {
            switch (指令) {
                case "beat":
                    心跳时间 = System.nanoTime();
                    break;
                case "addThread":
                    System.err.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> 新增连接:");
                    System.err.println("本地客户端代理新增连接:激活数量" + 公网代理线程池.getActiveCount() + " 核心线程数量:" + 公网代理线程池.getCorePoolSize() + " 最大线程数量:" + 公网代理线程池.getMaximumPoolSize());
                    System.err.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> 新增连接:");
                    创建代理服务器资源(); //主动提交一次代理创建
                    break;
                default:
                    System.err.println("未知的服务端指令:" + 指令);
                    break;
            }
        });
    }

    private static void 创建代理服务器资源() {
        try {
            公网代理线程池.execute(() -> {
                try {
                    连接远程代理服务器();
                } catch (Exception e) {
                    System.out.println("连接远程代理服务器失败:" + e.getMessage());
                }
            });
        } catch (Exception e) {
            System.out.println("连接远程代理服务器【任务提交】失败:" + e.getMessage());
        }
    }


    private static void 连接远程代理服务器() throws IOException {
        long[] start = new long[]{System.nanoTime()};
        int no = 本地代理线程编号.incrementAndGet();
        // 创建选择器
        Selector selector = Selector.open();
        // 创建SocketChannel并注册到选择器
        SocketChannel socketChannel = getSocketChannel(公网服务器地址, 公网服务器端口, selector);

        boolean stopLoop = false;
        // 处理事件循环
        while (!stopLoop) {
            try {
                selector.select(Env.通知超时时间);

                if (!socketChannel.isConnected() && !socketChannel.isConnectionPending()) {
                    关闭selector(selector);
                    break;
                }


                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    removeSelectKey(keyIterator);

                    if (key.isConnectable()) {
                        handleConnectEvent(key);
                        System.out.println("【公网】服务器 连接成功");
                        //    socketChannel.write(ByteBuffer.wrap(new byte[0]));
                        选取内网连接服务(socketChannel, no);
                    } else if (key.isReadable()) {
                        if (接收公网数据(key, start, no)) {
                            stopLoop = true;
                            break;
                        }
                    } else {
                        System.err.println("未知的公网服务key状态：" + key);
                    }

                }
            } catch (Exception e) {
                System.out.println("接收【公网】连接服务 处理异常");
                e.printStackTrace();
                System.out.println("接收【公网】连接服务 处理异常结束");
                关闭对内和对外流(socketChannel, "【公网异常】", selector);
                break;
            } /*finally {
                if (socketChannel.isConnected()) {

                }else {
                    System.out.println("公网连接已经断开..."+socketChannel);
                    break;
                }
            }*/
        }
    }

    private static void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void removeSelectKey(Iterator<SelectionKey> iterator) {
        if (Objects.nonNull(iterator)) {
            try {
                iterator.remove();
            } catch (Exception e) {
                e.printStackTrace();
                //  throw new RuntimeException("释放selectKey失败");
            }
        }
    }

    private static void 关闭对内和对外流(SocketChannel socket, String type, Selector selector) {
        try {

            SocketChannel remove = 公网_内网_SOCKET_映射.remove(socket);
            if (Objects.nonNull(remove)) {
                System.out.println(type + " 发起" + "关闭【内网】流:local" + remove.getLocalAddress() + " remote:" + remove.getRemoteAddress());
                remove.close();

            }

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("流关闭异常 2");
        }


        try {
            System.out.println(type + " 发起" + "关闭【公网】流:local" + socket.getLocalAddress() + " remote:" + socket.getRemoteAddress());
        } catch (Exception e) {
        }
        try {
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("流关闭异常 1");
        }
        try {
            selector.close();
        } catch (Exception e) {
            System.err.println("selector 关闭异常 " + e.getMessage());
        }


    }


    private static void handleConnectEvent(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        if (socketChannel.isConnectionPending()) {
            socketChannel.finishConnect();
        }

        socketChannel.configureBlocking(false);
        socketChannel.register(key.selector(), SelectionKey.OP_READ);
    }


    private static boolean 接收公网数据(SelectionKey key, long[] start, int no) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(Env.缓冲区大小);
        int bytesRead = socketChannel.read(buffer);

        SocketChannel 对内网服务器流 = 选取内网连接服务(socketChannel, no);
        if (对内网服务器流 == null) {
            throw new RuntimeException("内网服务器丢失，系统异常");
        }
        if (bytesRead > 0) {
            buffer.flip();
            byte[] data = new byte[bytesRead];
            buffer.get(data);
            System.out.println("【" + no + "】接收到 公网服务端[local:" + socketChannel.getLocalAddress() + " remote:" + socketChannel.getRemoteAddress() + "] 的数据：length:" + bytesRead + "  content:" + new String(data, StandardCharsets.UTF_8));
            if (!待推送的消息队列.offer(new ToBeSendMsg(对内网服务器流,ByteBuffer.wrap(data)))){
                System.err.println("系统负载超异常，请确定是否调整待推送消息大小");
            };
            start[0] = System.nanoTime();
        } else if (bytesRead == -1) {
            关闭对内和对外流(socketChannel, "公网", key.selector());
            return true;
        } else {
            System.err.println("公网怎么可能推送空数据过来呢");
        }
        return false;
    }

    private static boolean 接收内网数据(SelectionKey key, long[] start, int no, SocketChannel socket) throws IOException {
/*        if (getTimeCostMs(start) > BioClient_LAZY.Env.内网服务端最长空闲时间) {
            System.err.println("内网代理服务 空闲超时，关闭内网代理连接，关闭客户端代理连接");
            throw new RuntimeException("抛出异常关闭 对内网服务器流");
        }*/

        SocketChannel socketChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(Env.缓冲区大小);
        int bytesRead = socketChannel.read(buffer);

        if (bytesRead > 0) {
            buffer.flip();
            byte[] data = new byte[bytesRead];
            buffer.get(data);
            System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
            System.out.println("【" + no + "】接收到 内网服务端[local:" + socketChannel.getLocalAddress() + " remote:" + socketChannel.getRemoteAddress() + "] 的数据：length:" + bytesRead + "  content:" );
            System.out.println(new String(data, StandardCharsets.UTF_8));
            System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");

            if (!待推送的消息队列.offer(new ToBeSendMsg(socket,ByteBuffer.wrap(data)))){
                System.err.println("系统负载超异常，请确定是否调整待推送消息大小");
            };

            start[0] = System.nanoTime();
        } else if (bytesRead == -1) {
            System.out.println("内网连接断开");
            关闭对内和对外流(socket, "内网", key.selector());
            return true;
        } else {
            System.err.println("内网 怎么可能推送空数据过来呢");
        }
        return false;
    }

    private static SocketChannel 选取内网连接服务(SocketChannel socket, int no) throws IOException {
        SocketChannel originSocketInternal = 公网_内网_SOCKET_映射.get(socket);
        if (Objects.nonNull(originSocketInternal)) {
            return originSocketInternal;
        }
        启动内网目标服务处理线程(socket, no);
        originSocketInternal = 公网_内网_SOCKET_映射.get(socket);
        if (Objects.nonNull(originSocketInternal)) {
            return originSocketInternal;
        }
        throw new RemoteException("系统异常，内网连接失败");
    }


    private static void 启动内网目标服务处理线程(SocketChannel socket, int no) throws IOException {
        Selector selector = Selector.open();
        // 创建SocketChannel并注册到选择器
        SocketChannel socketChannelInternal = getSocketChannel(内网服务器地址, 内网服务器端口, selector);

        公网_内网_SOCKET_映射.put(socket, socketChannelInternal);
        Thread 公网服务器线程 = Thread.currentThread();
        内网目标服务器访问线程.execute(() -> {
            long[] start = new long[]{System.nanoTime()};
            boolean runLoop = true;
            try {
                // 处理事件循环
                while (runLoop) {
                    selector.select(Env.通知超时时间);

                    if (!socketChannelInternal.isConnected() && !socketChannelInternal.isConnectionPending()) {
                        关闭selector(selector);
                        break;
                    }

                    Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        removeSelectKey(keyIterator);

                        if (key.isConnectable()) {
                            handleConnectEvent(key);
                            System.out.println("【内网】服务器 连接成功");
                            LockSupport.unpark(公网服务器线程);
                        } else if (key.isReadable()) {
                            if (接收内网数据(key, start, no, socket)) {
                                runLoop = false;
                                break;
                            }
                        } else {
                            System.err.println("未知的公网服务key状态：" + key);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("接收【内网】连接服务 处理异常");
                e.printStackTrace();
                System.err.println("接收【内网】连接服务 处理异常结束");
                关闭对内和对外流(socket, "内网异常 直接break", selector);
            }
        });
        LockSupport.park();//必须等待内网连接完成，后续才能处理数据
    }

    private static void 关闭selector(Selector selector) {
        if (selector != null) {
            try {
                selector.close();
            } catch (Exception e) {
                System.out.println("selector关闭失败");
            }
        }
    }

    private static SocketChannel getSocketChannel(String ip, Integer port, Selector selector) throws IOException {
        SocketChannel socketChannelInternal = SocketChannel.open();
        socketChannelInternal.configureBlocking(false);
        socketChannelInternal.connect(new InetSocketAddress(ip, port));
        socketChannelInternal.register(selector, SelectionKey.OP_CONNECT);
        return socketChannelInternal;
    }


    static class ToBeSendMsg {
        public ToBeSendMsg(SocketChannel socketChannel, ByteBuffer buffer) {
            this.socketChannel = socketChannel;
            this.buffer = buffer;
        }

        SocketChannel socketChannel;
        ByteBuffer buffer;
    }

}