import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * @author : chendm3
 * @since : 2024/1/22 9:17
 */
public class NioServer {
    private static boolean debugEnable = true;
    private static Integer 客户端端口 = 8001;

    private static Integer 用户端口 = 443;
    //private static Integer 用户端口 = 8887;
    private static Integer 心跳端口 = 8331;

    private static final byte[] 空缓存数据 = new byte[0];

    private static final Map<SocketChannel, SocketChannel> 对用户输出缓存 = new ConcurrentHashMap<>();
    private static final Map<SocketChannel, SocketChannelWrapper> 对客户端代理输出缓存 = new ConcurrentHashMap<>();
    private static final Map<SocketChannelWrapper, SocketChannel> 客户端_对用户输出_映射 = new ConcurrentHashMap<>();
    private static final Set<SocketChannelWrapper> 对客户端代理缓存_池 = new HashSet<>();
    private static final Map<SocketChannel, SocketChannelWrapper> 客户端对用户端_数据_缓存池 = new ConcurrentHashMap<>();
    private static final Queue<Thread> 待唤醒_客户端代理线程 = new ArrayBlockingQueue<>(512);
    private static final Map<SocketChannelWrapper, Thread> 待唤醒_用户代理线程 = new ConcurrentHashMap<>();

    private static final ArrayBlockingQueue<BeatMsgWrapper> 待通知客户端消息 = new ArrayBlockingQueue<>(512);


    private static final int 最小空闲客户端代理数量 = 0;

    private static volatile SocketChannel 客户端心跳Socket = null;

    private static final String 字符串分隔符 = ";";

    private static class 统计指标 {
        private static final List<String> 客户端存活时间 = new LinkedList<String>() {
            @Override
            public boolean add(String o) {
                if (this.size() > 10000) {
                    return false;
                }
                return super.add(o);
            }
        };
        private static final List<Integer> 客户端连接耗时 = new LinkedList<Integer>() {
            @Override
            public boolean add(Integer o) {
                if (this.size() > 10000) {
                    return false;
                }
                return super.add(o);
            }
        };
    }

    private static final ThreadPoolExecutor 客户端_用户端消息处理线程池 = new ThreadPoolExecutor(6, 12, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread thread = new Thread(r);
        thread.setName("指令处理线程:" + ThreadLocalRandom.current().nextInt(1024));
        return thread;
    }, (r, executor) -> {
        System.out.println("任务线程执行任务:" + Thread.currentThread().getName());
        r.run();
    });


    public static void main(String[] args) {
        // 用户端口=  心跳端口  客户端端口
        用户端口 = Integer.parseInt(System.getProperty("proxy.user.port", 用户端口.toString()));
        心跳端口 = Integer.parseInt(System.getProperty("proxy.beat.port", 心跳端口.toString()));
        客户端端口 = Integer.parseInt(System.getProperty("proxy.client.port", 客户端端口.toString()));
        接收客户端代理连接服务();
        启动心跳服务();
        客户端消息心跳消息推送任务();

        sleep(1000);
        接收用户请求服务();
    }

    private static void 启动心跳服务() {
        new Thread(() -> {
            try {
                ServerSocketChannel serverSocket = ServerSocketChannel.open();
                serverSocket.socket().bind(new InetSocketAddress(心跳端口));
                serverSocket.configureBlocking(false);
                Selector selector = Selector.open();
                serverSocket.register(selector, SelectionKey.OP_ACCEPT);
                System.out.println("【心跳端】 server start success");
                new Thread(() -> 处理客户端心跳(selector), "接收心跳连接服务").start();
            } catch (Exception e) {
                System.err.println("心跳基础服务启动失败");
                e.printStackTrace();
            }
        }, "心跳基础线程").start();
    }


    private static void 接收用户请求服务() {
        try {
            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            serverSocket.socket().bind(new InetSocketAddress(用户端口));
            serverSocket.configureBlocking(false);
            Selector selector = Selector.open();
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("【用户端】 server start success");
            new Thread(() -> process接收用户请求(selector), "接收用户连接线程").start();
        } catch (Exception e) {
            System.out.println("【用户端】 server start fail");
            e.printStackTrace();
        }

    }

    private static void process接收用户请求(Selector selector) {
        while (true) {
            Iterator<SelectionKey> iterator = null;
            SocketChannel socketChannel = null;
            try {
                selector.select();
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                iterator = selectionKeys.iterator();

                // 遍历SelectionKey对事件进行处理
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    removeSelectKey(iterator);
                    // 如果是OP_ACCEPT事件，则进行连接获取和事件注册
                    if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        socketChannel = server.accept();//todo  k1 怎么会accept出来一个空channel呢
                        socketChannel.configureBlocking(false);
                        // 这里只注册了读事件，如果需要给客户端发送数据可以注册写事件
                        socketChannel.register(selector, SelectionKey.OP_READ);
                        cacheUserIdentity(socketChannel);
                        fresh客户端暂存数据(socketChannel, 选择合适的一个客户端代理(socketChannel).socketChannel);
                        System.out.println("【用户端】端口 client connect success: local" + socketChannel.socket().getLocalSocketAddress() + " remote:" + socketChannel.socket().getRemoteSocketAddress());
                    } else if (key.isReadable()) {  // 如果是OP_READ事件，则进行读取和打印
                        socketChannel = (SocketChannel) key.channel();
                        ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
                        int len = socketChannel.read(byteBuffer);
                        // 如果有数据，把数据打印出来

                        if (len == -1) {
                            释放用户连接(socketChannel);
                        } else if (len > 0) {
                            socketChannel = 选择合适的一个客户端代理(socketChannel).socketChannel;
                            byteBuffer.flip();
                            byte[] receivedData = new byte[byteBuffer.remaining()];
                            byteBuffer.get(receivedData);
                            if (debugEnable) {
                                System.out.println("接收到【用户端】[" + socketChannel.socket().getRemoteSocketAddress() + "]的数据：" + "  数据长度:" + len + " content: \r\n" + new String(receivedData));
                            }
                            socketChannel.write(ByteBuffer.wrap(receivedData));
                        } else {
                            System.err.println("用户端怎么可能推送空数据呢");
                        }
                    } else {
                        System.out.println("【用户端】端口 event: isConnectable" + key.isConnectable() + "- isWritable" + key.isWritable());
                    }
                    //从事件集合里删除本次处理的key，防止下次select重复处理
                }
            } catch (Exception e) {
                System.err.println("接收【用户端】代理连接服务 处理异常");
                e.printStackTrace();
                System.err.println("接收【用户端】代理连接服务 处理异常结束");
                释放用户连接(socketChannel);
            }
        }
    }

    private static void removeSelectKey(Iterator<SelectionKey> iterator) {
        if (Objects.nonNull(iterator)) {
            try {
                iterator.remove();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private static void 释放空闲异常客户端连接(SocketChannel clientSocket) {
        try {
            if (clientSocket == null) {
                return;
            }
            SocketChannelWrapper channelWrapper = 客户端对用户端_数据_缓存池.remove(clientSocket);
            String msg = "【客户端】客户端端口 Client disconnected:" + clientSocket.socket().getRemoteSocketAddress() + " 客户端存活时间: " +
                    (Optional.ofNullable(channelWrapper).map(item -> item.createTime).map(item -> new Date().getTime() - item.getTime()).orElse(-1L)) / 1000 + " 秒";
            System.err.println(msg);

            统计指标.客户端存活时间.add(msg);

            对客户端代理缓存_池.remove(channelWrapper);
            SocketChannel userIdentity = 客户端_对用户输出_映射.remove(channelWrapper);
            try {
                if (userIdentity != null) {
                    SocketChannel remove = 对用户输出缓存.remove(userIdentity);
                    if (remove != null) remove.close();
                }

            } catch (Exception e) {
                System.err.println("释放空闲异常客户端连接：关闭浏览器流失败");
                e.printStackTrace();
            }
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("释放空闲异常客户端连接：关闭客户端流失败");
                e.printStackTrace();
            }
            if (userIdentity != null) {
                对客户端代理输出缓存.remove(userIdentity);
            }
        } catch (Exception e) {
            System.err.println("释放空闲异常客户端连接：关闭客户端连接异常 start");
            e.printStackTrace();
            System.err.println("释放空闲异常客户端连接：关闭客户端连接异常 end");
        }
    }

    private static void 释放用户连接(SocketChannel clientSocket) {
        try {
            System.out.println("释放用户连接:" + clientSocket);
            if (clientSocket == null) {
                return;
            }

            SocketChannel clientIdentity = getClientIdentity(clientSocket);
            SocketChannelWrapper remove = 对客户端代理输出缓存.remove(clientIdentity);

            String msg = "【用户端】端口 Client disconnected" + clientSocket.socket().getRemoteSocketAddress() + " 客户端存活时间: " +
                    (Optional.ofNullable(remove).map(item -> item.createTime).map(item -> new Date().getTime() - item.getTime()).orElse(-1L)) / 1000 + " 秒";
            System.err.println(msg);
            统计指标.客户端存活时间.add(msg);

            try {
                if (Objects.nonNull(remove)) {
                    客户端_对用户输出_映射.remove(remove);
                    对客户端代理缓存_池.remove(remove);
                    客户端对用户端_数据_缓存池.remove(remove.socketChannel);
                    remove.socketChannel.close();
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
        } catch (Exception e) {
            System.err.println("释放用户连接失败===================");
            e.printStackTrace();
            System.err.println("释放用户连接失败===================");
        }


    }

    private static void 接收客户端代理连接服务() {
        try {
            ServerSocketChannel serverSocket = ServerSocketChannel.open();

            serverSocket.socket().bind(new InetSocketAddress(客户端端口));
            serverSocket.configureBlocking(false);
            Selector selector = Selector.open();
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("【客户端】 server start success");
            new Thread(() -> process客户端请求(selector), "接收客户端连接服务").start();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("【客户端】 server start fail");
            throw new RuntimeException(e);
        }


    }

    private static void 释放心跳客户端(SocketChannel socketChannel) {
        if (socketChannel != null) {
            try {
                System.out.println("释放心跳客户端" + socketChannel);
                socketChannel.close();
            } catch (Exception e) {
                System.out.println("释放心跳客户端失败:" + e.getMessage());
            }
        }
    }

    private static void 处理客户端心跳(Selector selector) {
        while (true) {
            Iterator<SelectionKey> iterator = null;
            SocketChannel socketChannel = null;
            try {
                selector.select();
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                iterator = selectionKeys.iterator();

                // 遍历SelectionKey对事件进行处理
                while (iterator.hasNext()) {
                    socketChannel = null;
                    SelectionKey key = iterator.next();
                    removeSelectKey(iterator);
                    // 如果是OP_ACCEPT事件，则进行连接获取和事件注册
                    if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        socketChannel = server.accept();
                        socketChannel.configureBlocking(false);
                        // 这里只注册了读事件，如果需要给客户端发送数据可以注册写事件
                        socketChannel.register(selector, SelectionKey.OP_READ);
                        System.out.println("【心跳端】连接了。。local:" + socketChannel.socket().getLocalSocketAddress() + " remote:" + socketChannel.socket().getRemoteSocketAddress());
                        客户端心跳Socket = socketChannel;
                    } else if (key.isReadable()) {  // 如果是OP_READ事件，则进行读取和打印
                        socketChannel = (SocketChannel) key.channel();

                        ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
                        int len = socketChannel.read(byteBuffer);
                        // 如果有数据，把数据打印出来
                        if (len == -1) {
                            释放心跳客户端(socketChannel);
                        } else if (len == 0) {
                            System.err.println("心跳客户端怎么可能推送空数据呢");
                        } else {
                            byteBuffer.flip();
                            byte[] receivedData = new byte[byteBuffer.remaining()];
                            byteBuffer.get(receivedData);
                            String msg = new String(receivedData);
                            if (ThreadLocalRandom.current().nextInt(120) == 1 && !msg.isEmpty()) {
                                System.out.println("线程:" + Thread.currentThread().getName() + " remote:" + socketChannel.getRemoteAddress() + " 接收到客户端消息：" + msg);
                            }
                            // System.out.println("接收到心跳客户端消息:"+msg);
                            for (String 客户端指令 : msg.split(字符串分隔符)) {
                                if ("beat".equals(客户端指令)) {
                                    if (!待通知客户端消息.offer(new BeatMsgWrapper("beat"))) {
                                        System.err.println("客户端心跳推送【队列】失败");
                                    }
                                } else {
                                    System.err.println("未知的客户端指令:" + 客户端指令);
                                }
                            }
                        }
                    } else {
                        System.out.println("【心跳端】端口 event: isConnectable" + key.isConnectable() + "- isWritable" + key.isWritable());
                    }
                    //从事件集合里删除本次处理的key，防止下次select重复处理
                }
            } catch (Exception e) {
                System.out.println("接收【心跳端】代理连接服务 处理异常");
                e.printStackTrace();
                System.out.println("接收【心跳端】代理连接服务 处理异常结束");
                释放心跳客户端(socketChannel);
            }
        }
    }


    private static void process客户端请求(Selector selector) {
        while (true) {
            Iterator<SelectionKey> iterator;
            SocketChannel socketChannel = null;
            try {
                selector.select();
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                iterator = selectionKeys.iterator();

                // 遍历SelectionKey对事件进行处理
                while (iterator.hasNext()) {
                    socketChannel = null;
                    SelectionKey key = iterator.next();
                    removeSelectKey(iterator);
                    // 如果是OP_ACCEPT事件，则进行连接获取和事件注册
                    if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        socketChannel = server.accept();
                        socketChannel.configureBlocking(false);
                        // 这里只注册了读事件，如果需要给客户端发送数据可以注册写事件
                        socketChannel.register(selector, SelectionKey.OP_READ);
                        缓存客户端代理(socketChannel);
                        System.out.println("【客户端】连接成功。。local:" + socketChannel.socket().getLocalSocketAddress() + " remote:" + socketChannel.socket().getRemoteSocketAddress());
                    } else if (key.isReadable()) {  // 如果是OP_READ事件，则进行读取和打印
                        socketChannel = (SocketChannel) key.channel();
                        处理客户端消息(socketChannel);
                    } else {
                        System.out.println("【客户端】端口 event: isConnectable" + key.isConnectable() + "- isWritable" + key.isWritable());
                    }
                    //从事件集合里删除本次处理的key，防止下次select重复处理
                }
            } catch (Exception e) {
                System.out.println("接收【客户端】代理连接服务 处理异常");
                e.printStackTrace();
                System.out.println("接收【客户端】代理连接服务 处理异常结束");
                释放空闲异常客户端连接(socketChannel);
            }
        }
    }

    private static void 处理客户端消息(SocketChannel socketChannel) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
        int len = socketChannel.read(byteBuffer);
        // 如果有数据，把数据打印出来
        if (len == -1) {
            释放空闲异常客户端连接(socketChannel);
        } else if (len > 0) {
            byteBuffer.flip();
            byte[] receivedData = new byte[byteBuffer.remaining()];
            byteBuffer.get(receivedData);
            if (debugEnable) {
                System.out.println("接收到【客户端】[" + socketChannel.socket().getRemoteSocketAddress() + "]的数据：" + "  数据长度:" + len + " content: \r\n" + new String(receivedData));
            }

            SocketChannel 对用户浏览器流 = 查找对用户浏览器流(socketChannel);
            // String response = "1";
            //  ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
            if (Objects.nonNull(对用户浏览器流)) {
                fresh客户端暂存数据(对用户浏览器流, socketChannel);
                对用户浏览器流.write(ByteBuffer.wrap(receivedData));
            } else {
                缓存用户数据(socketChannel, receivedData);
            }
        } else {
            System.err.println("【客户端】 怎么可能推送空数据呢");
        }
    }

    private static void fresh客户端暂存数据(SocketChannel 对用户浏览器流, SocketChannel 对客户端流) throws IOException {
        byte[] array = 查找客户端缓存数据(对客户端流);
        if (Objects.isNull(array) || array.length == 0) {
            return;
        }
        System.out.println("发送用户，代理客户端暂存数据:" + new String(array));
        对用户浏览器流.write(ByteBuffer.wrap(array));
    }

    private static byte[] 查找客户端缓存数据(SocketChannel socketChannel) {
        SocketChannelWrapper channelWrapper = 客户端对用户端_数据_缓存池.get(socketChannel);
        if (Objects.isNull(channelWrapper)) {
            System.out.println("系统异常，用户缓存数据丢失,极限场景问题:" + socketChannel.socket().getLocalSocketAddress() + " remote:" + socketChannel.socket().getRemoteSocketAddress());
            return null;
        }
        byte[] cacheContent = channelWrapper.cacheContent;
        channelWrapper.cacheContent = 空缓存数据;
        return cacheContent;
    }

    private static void 缓存用户数据(SocketChannel socketChannel, byte[] receivedData) {
        System.out.println("缓存用户数据");
        if (receivedData.length == 0) {
            return;
        }

        SocketChannelWrapper channelWrapper = 客户端对用户端_数据_缓存池.get(socketChannel);
        if (Objects.isNull(channelWrapper)) {
            System.out.println("系统异常，用户缓存数据丢失,极限场景问题:" + socketChannel.socket().getLocalSocketAddress() + " remote:" + socketChannel.socket().getRemoteSocketAddress());
        } else {
            byte[] bytes = new byte[channelWrapper.cacheContent.length + receivedData.length];
            int offset = 0;
            for (byte datum : channelWrapper.cacheContent) {
                bytes[offset++] = datum;
            }

            for (byte datum : receivedData) {
                bytes[offset++] = datum;
            }
            channelWrapper.cacheContent = bytes;
        }
    }

    private static void 缓存客户端代理(SocketChannel socketChannel) {
        SocketChannelWrapper channelWrapper = new SocketChannelWrapper(socketChannel);
        对客户端代理缓存_池.add(channelWrapper);
        客户端对用户端_数据_缓存池.put(socketChannel, channelWrapper);
        待唤醒_用户代理线程.put(channelWrapper, Thread.currentThread());
        唤醒_存在空闲代理();

        System.out.println("挂起当前线程【" + Thread.currentThread().getName() + "】，等待用户端缓存完成通知");
        LockSupport.parkNanos(2000_000_000);
        待唤醒_用户代理线程.remove(channelWrapper);
    }

    private static void 唤醒_存在空闲代理() {
        Optional.ofNullable(待唤醒_客户端代理线程.poll()).ifPresent(thread -> {
            System.out.println("===>>> 唤醒阻塞的用户线程" + thread);
            LockSupport.unpark(thread);
        });
    }


    private static long getTimeCostMs(long[] idleStart) {
        return (System.nanoTime() - idleStart[0]) / 1000_000;
    }


    private static SocketChannel 查找对用户浏览器流(SocketChannel clientSocket) {
        SocketChannelWrapper channelWrapper = 客户端对用户端_数据_缓存池.get(clientSocket);
        if (Objects.isNull(channelWrapper)) {
            return null;
        }
        SocketChannel userIdentity = 客户端_对用户输出_映射.get(channelWrapper);
        if (Objects.nonNull(userIdentity)) {
            return 对用户输出缓存.get(userIdentity);
        }
        return null;
    }


    private static SocketChannelWrapper 选择合适的一个客户端代理(SocketChannel clientSocket) {
        SocketChannel identity = getClientIdentity(clientSocket);
        SocketChannelWrapper outputStream = 对客户端代理输出缓存.get(identity);
        if (Objects.isNull(outputStream)) {
            synchronized (对客户端代理缓存_池) {

                if (对客户端代理缓存_池.size() <= 最小空闲客户端代理数量) {
                    推送一个新增客户端代理的请求(clientSocket);
                }
                long l = System.nanoTime();
                SocketChannelWrapper 缓存输出流 = 对客户端代理缓存_池.stream().findFirst().orElseGet(() -> {
                    if (待唤醒_客户端代理线程.offer(Thread.currentThread())) {
                        System.out.println("成功挂起当前线程【" + Thread.currentThread().getName() + "】，等待客户端代理新建完成通知");
                        LockSupport.parkNanos(2000_000_000);
                    }
                    return 对客户端代理缓存_池.stream().findFirst().orElseThrow(() -> new RuntimeException("没有可用客户端池连接"));
                });
                Long timeCostMs = getTimeCostMs(new long[]{l});
                统计指标.客户端连接耗时.add(timeCostMs.intValue());
                System.err.println("pick客户端代理success：" + timeCostMs);
                对客户端代理缓存_池.remove(缓存输出流);

                对客户端代理输出缓存.put(identity, 缓存输出流);
                outputStream = 缓存输出流;
                客户端_对用户输出_映射.put(缓存输出流, identity);

                Optional.ofNullable(待唤醒_用户代理线程.remove(缓存输出流)).ifPresent(LockSupport::unpark);
            }
        }
        return outputStream;
    }

    /**
     * 服务端代理参数暂未使用，后续会有多租户的情况
     *
     * @param clientSocket
     */
    private static void 推送一个新增客户端代理的请求(SocketChannel clientSocket) {
        try {
            BeatMsgWrapper beatMsg = new BeatMsgWrapper("addThread");
            if (!待通知客户端消息.offer(beatMsg)) {
                System.err.println("===>>> 给客户端发送新增代理的请求【提交队列失败】:" + 客户端心跳Socket/*Optional.ofNullable(客户端心跳Socket).map(Socket::getRemoteSocketAddress).orElse(null)*/);
            } else {
                System.out.println("===>>> 给客户端发送新增代理的请求:" + 客户端心跳Socket/* Optional.ofNullable(客户端心跳Socket).map(Socket::getRemoteSocketAddress).orElse(null)*/);
            }
        } catch (Exception e) {
            System.err.println("===>>> 给客户端发送新增代理的请求: 执行失败" + e.getMessage());
        }

    }

    private static void 客户端消息心跳消息推送任务() {
        new Thread(() -> {
            while (true) {
                BeatMsgWrapper task = null;
                try {
                    if (Objects.isNull(客户端心跳Socket)) {
                        sleep(600);
                        continue;
                    }
                    task = 待通知客户端消息.take();
                    客户端心跳Socket.write(ByteBuffer.wrap((task.message + 字符串分隔符).getBytes(StandardCharsets.UTF_8)));
                    //   System.out.println("给心跳客户端"+客户端心跳Socket+"推送消息:"+task.message);
                } catch (Exception e) {
                    System.err.println("客户端消息心跳消息推送任务 发送失败 消息【" + Optional.ofNullable(task).map(item -> item.message).orElse("") + "】" + e.getMessage());
                    sleep(600);
                }
            }
        }, "客户端心跳消息推送线程").start();
    }


    private static void cacheUserIdentity(SocketChannel clientSocket) throws IOException {
        对用户输出缓存.put(getClientIdentity(clientSocket), clientSocket);
    }

    private static SocketChannel getClientIdentity(SocketChannel clientSocket) {
        return clientSocket;
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

    private static class SocketChannelWrapper {
        public SocketChannelWrapper(SocketChannel socketChannel) {
            this.socketChannel = socketChannel;
            this.cacheContent = 空缓存数据;
            this.createTime = new Date();// new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        }

        SocketChannel socketChannel;
        byte[] cacheContent;
        Date createTime;
    }

    private static class BeatMsgWrapper {

        String message;

        public BeatMsgWrapper(String message) {
            this.message = message;
        }
    }
}
