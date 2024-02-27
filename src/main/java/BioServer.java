import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author : chendm3
 * @since : 2022/8/24 16:39
 */
public class BioServer {

    private static final Integer 用户端口 = 443;

    private static final Map<String, Socket> 对用户输出缓存 = new ConcurrentHashMap<>();
    private static final Map<String, Socket> 对客户端代理输出缓存 = new ConcurrentHashMap<>();
    private static final Map<Socket, String> 客户端_对用户输出_映射 = new ConcurrentHashMap<>();
    private static final HashSet<Socket> 对客户端代理缓存_池 = new HashSet<>();


    public static void main(String[] args) throws IOException, InterruptedException {
        接收客户端代理连接服务();
        Thread.sleep(3333);
        接收用户请求();
    }

    private static void 接收用户请求() throws IOException {
        ServerSocket serverSocket = new ServerSocket(用户端口);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            处理用户连接(clientSocket);
        }
    }

    private static void 处理用户连接(Socket clientSocket) {
        System.out.println("有用户连接了。。");
        new Thread(() -> {
            int[] 计数 = {0};
            long[] idleStart = {System.nanoTime()};
            try {
                while (true) {
                    handler(clientSocket, CONNECT_TYPE.浏览器访问, 计数, idleStart);
                }
            } catch (Exception e) {
                if (!Optional.ofNullable(e.getMessage()).orElse("").contains("抛出异常释放")) {
                    System.out.println("处理用户连接 处理异常");
                    e.printStackTrace();
                    System.out.println("处理用户连接 处理异常结束");
                }
            } finally {
                释放用户连接(clientSocket);
            }

        }, "用户访问处理线程" + clientSocket.getRemoteSocketAddress()).start();
    }

    private static void 释放空闲异常客户端连接(Socket clientSocket) {
        try {

            对客户端代理缓存_池.remove(clientSocket);
            String userIdentity = 客户端_对用户输出_映射.remove(clientSocket);
            try {
                if (userIdentity != null) {
                    Socket remove = 对用户输出缓存.remove(userIdentity);
                    if (remove != null) remove.close();
                }

            } catch (Exception e) {
                System.out.println("释放空闲异常客户端连接：关闭浏览器流失败");
                e.printStackTrace();
            }
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("释放空闲异常客户端连接：关闭客户端流失败");
                e.printStackTrace();
            }
            if (userIdentity != null) {
                对客户端代理输出缓存.remove(userIdentity);
            }
        } catch (Exception e) {
            System.out.println("释放空闲异常客户端连接：关闭客户端连接异常");
            e.printStackTrace();
        }
    }

    private static void 释放用户连接(Socket clientSocket) {
        System.out.println("释放用户连接");
        String clientIdentity = getClientIdentity(clientSocket);
        Socket remove = 对客户端代理输出缓存.remove(clientIdentity);
        try {
            if (Objects.nonNull(remove)) {
                客户端_对用户输出_映射.remove(remove);
                对客户端代理缓存_池.remove(remove);
                remove.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        对用户输出缓存.remove(clientIdentity);

        try {
            clientSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    private static void 接收客户端代理连接服务() {
        new Thread(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(8000);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    处理客户端连接(clientSocket);
                }
            } catch (Exception e) {
                System.out.println("接收客户端代理连接服务 处理异常");
                e.printStackTrace();
                System.out.println("接收客户端代理连接服务 处理异常结束");
                throw new RuntimeException(e);
            }
        }, "客户端接收连接服务").start();
    }

    private static void 处理客户端连接(Socket clientSocket) {
        System.out.println("有客户端连接了。。" + clientSocket.getRemoteSocketAddress());
        new Thread(() -> {
            long[] idleStart = {System.nanoTime()};
            int[] 计数 = {0};
            try {
                while (true) {
                    handler(clientSocket, CONNECT_TYPE.客户端访问, 计数, idleStart);
                }
            } catch (Exception e) {
                if (!Optional.ofNullable(e.getMessage()).orElse("").contains("抛出异常释放")) {
                    System.out.println("处理客户端连接 处理异常");
                    e.printStackTrace();
                    System.out.println("处理客户端连接 处理异常结束");
                }
            } finally {
                释放空闲异常客户端连接(clientSocket);
            }

        }, "客户端访问处理线程:" + clientSocket.getRemoteSocketAddress()).start();
    }


    private static void handler(Socket clientSocket, CONNECT_TYPE connectType, int[] count, long[] idleStart) throws IOException {


        InputStream inputStream = clientSocket.getInputStream();

        byte[] bytes = new byte[inputStream.available()];
        int read = inputStream.read(bytes);

        if (read >= 0) {
            count[0]++;
            if (read > 0 || count[0] % 5000 == 1) {
                System.out.println("接收到【" + connectType + "】[" + clientSocket.getRemoteSocketAddress() + "]的数据：" + "  数据长度:" + read + " content: \r\n" + new String(bytes, 0, read));
            }

            switch (connectType) {
                case 浏览器访问:

                    cacheUserIdentity(clientSocket);
                    Socket 对客户端代理输出流 = 选择合适的一个客户端代理(clientSocket);
                    ;
                    if (对客户端代理输出流 == null) {
                        System.err.println(" 客户端代理尚未连接!!!");
                        throw new RuntimeException("客户端代理尚未连接");
                    } else if (read >= 0) {
                        对客户端代理输出流.getOutputStream().write(bytes);
                        对客户端代理输出流.getOutputStream().flush();
                    }

                    if (read > 0) {
                        idleStart[0] = System.nanoTime();
                    }

                    if (read == 0) {
                        sleep(30);
                        if (getTimeCostMs(idleStart) > 60_000) {
                            //释放空闲连接(clientSocket);
                            throw new RemoteException("抛出异常释放[" + connectType + "]连接");
                        }
                    }

                    break;
                case 客户端访问:
                    Socket 对用户浏览器输出流 = 查找对用户浏览器流(clientSocket);
                    if (对用户浏览器输出流 == null) {
                        if (read > 0 || count[0] % 5000 == 1) {
                            System.err.println(" 用户尚未访问!!!");
                        }
                    } else if (read >= 0) {
                        对用户浏览器输出流.getOutputStream().write(bytes);
                        对用户浏览器输出流.getOutputStream().flush();
                    }
                    if (read > 0) {
                        idleStart[0] = System.nanoTime();
                    }

                    if (read == 0) {
                        if (getTimeCostMs(idleStart) > 60_000) {
                            //释放客户端空闲连接(clientSocket);
                            throw new RemoteException("抛出异常释放[" + connectType + "]连接");
                        }
                        sleep(30);
                    }

                    break;
                default:
                    System.err.println("怎么可能" + connectType);
                    break;
            }
        }


    }

    private static long getTimeCostMs(long[] idleStart) {
        return (System.nanoTime() - idleStart[0]) / 1000_000;
    }


    private static Socket 查找对用户浏览器流(Socket clientSocket) {
        String userIdentity = 客户端_对用户输出_映射.get(clientSocket);
        if (Objects.nonNull(userIdentity)) {
            return 对用户输出缓存.get(userIdentity);
        }
        对客户端代理缓存_池.add(clientSocket);
        return null;
    }

   /* private static void 释放空闲连接(Socket clientSocket) {

        String identity = getClientIdentity(clientSocket);
        Socket outputStream = 对客户端代理输出缓存.remove(identity);
        if (Objects.isNull(outputStream)) {
            return;
        }
        synchronized (对客户端代理缓存_池) {
            客户端_对用户输出_映射.remove(outputStream);
            对客户端代理缓存_池.add(outputStream);
        }
        Socket remove = 对用户输出缓存.remove(identity);
        if (Objects.nonNull(remove)) {
            try {
                remove.close();
            } catch (Exception e) {
                System.out.println("关闭用户流失败");
                e.printStackTrace();
                System.out.println("关闭用户流失败");
            }
        }
    }
*/


    private static Socket 选择合适的一个客户端代理(Socket clientSocket) {
        String identity = getClientIdentity(clientSocket);
        Socket outputStream = 对客户端代理输出缓存.get(identity);
        if (Objects.isNull(outputStream)) {
            synchronized (对客户端代理缓存_池) {
                if (对客户端代理缓存_池.isEmpty()) {
                    System.err.println("没有可用的客户端代理连接了");
                } else {
                    Socket 缓存输出流 = 对客户端代理缓存_池.stream().findFirst()
                            .orElseThrow(() -> new RuntimeException("没有可用客户端池连接"));
                    对客户端代理缓存_池.remove(缓存输出流);

                    对客户端代理输出缓存.put(identity, 缓存输出流);
                    outputStream = 缓存输出流;
                    客户端_对用户输出_映射.put(缓存输出流, identity);
                }
            }
        }
        return outputStream;
    }


    private static void cacheUserIdentity(Socket clientSocket) throws IOException {
        对用户输出缓存.put(getClientIdentity(clientSocket), clientSocket);
    }

    private static String getClientIdentity(Socket clientSocket) {
        return clientSocket.getRemoteSocketAddress().toString();
    }

    private static void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    enum CONNECT_TYPE {
        客户端访问, 浏览器访问
    }
}
