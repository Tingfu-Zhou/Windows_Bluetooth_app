package com.example.bletest;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Windows BLE测试应用 - JavaFX界面
 * 通过Python子进程实现BLE通信
 * [更新] 支持控制仲裁功能
 */
public class BLETestApp extends Application {

    private static final Logger LOGGER = Logger.getLogger(BLETestApp.class.getName());

    // ====== 协议常量（与Android版本一致）======
    private static final byte VER = 0x01;
    private static final byte CMD_SET_PATTERN = 0x04;
    private static final byte CMD_STOP_ALL = 0x02;
    private static final byte CMD_QUERY_STATE = 0x03;
    private static final byte CMD_STATE_RPT = (byte)0x83;
    private static final byte CMD_HEARTBEAT = 0x06;
    private static final byte CMD_RESUME_APP = 0x12;  // [NEW] 恢复App控制

    // [NEW] StateReport扩展解析用常量
    private static final int SRC_FW = 0, SRC_APP = 1, SRC_BUTTON = 2, SRC_SAFETY = 3;
    private static final int OWNER_IDLE = 0, OWNER_APP = 1, OWNER_LOCAL = 2;
    private static final int HOLD_NONE = 0, HOLD_TIMED = 1, HOLD_MANUAL = 2;

    // 模式和强度定义
    private static final byte PATTERN_1 = 1;
    private static final byte PATTERN_2 = 2;
    private static final byte PATTERN_3 = 3;

    private static final byte LEVEL_STOP = 0;
    private static final byte LEVEL_L = 1;
    private static final byte LEVEL_M = 2;
    private static final byte LEVEL_H = 3;

    // ====== UI组件 ======
    private Label lblConnStatus;
    private Label lblNeedAction;
    private Label lblLastAction;
    private Button btnConnect;
    private Button btnScan;
    private Button btnResume;  // [NEW] 恢复按钮
    private TextArea logArea;
    private CheckBox autoScrollCheckBox;

    // ====== Python进程管理 ======
    private Process pythonProcess;
    private BufferedReader pythonReader;
    private BufferedWriter pythonWriter;
    private final Gson gson = new Gson();
    private Thread pythonReaderThread;

    // ====== 状态管理 ======
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isPythonReady = new AtomicBoolean(false);
    private volatile boolean pausedByLocal = false;  // [NEW] 暂停标志
    private volatile String nextAction = "—";
    private volatile String lastAction = "—";
    private byte seq = 0;

    // ====== 调度器 ======
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> randomActionTask;
    private ScheduledFuture<?> uiUpdateTask;
    private final Random random = new Random();

    // ====== 接收数据缓冲 ======
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("BLE测试工具 - Windows版 (Python + Bleak)");

        // 创建UI
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        // [NEW] 顶部恢复按钮区域
        HBox topBox = new HBox();
        topBox.setAlignment(Pos.CENTER_RIGHT);

        btnResume = new Button("已暂停app操作，点击恢复");
        btnResume.setStyle("-fx-background-color: #FF8C00; -fx-text-fill: white; -fx-font-weight: bold;");
        btnResume.setPrefWidth(200);
        btnResume.setVisible(false);  // 默认隐藏
        btnResume.setOnAction(e -> sendResumeAppControl());

        topBox.getChildren().add(btnResume);

        // 连接控制区
        HBox controlBox = new HBox(10);
        controlBox.setAlignment(Pos.CENTER);

        btnScan = new Button("扫描设备");
        btnScan.setPrefWidth(100);
        btnScan.setOnAction(e -> scanDevices());

        btnConnect = new Button("连接 / 断开");
        btnConnect.setPrefWidth(120);
        btnConnect.setOnAction(e -> handleConnectButton());
        btnConnect.setDisable(true); // 初始禁用，等待Python就绪

        Button btnClearLog = new Button("清空日志");
        btnClearLog.setOnAction(e -> logArea.clear());

        autoScrollCheckBox = new CheckBox("自动滚动");
        autoScrollCheckBox.setSelected(true);

        controlBox.getChildren().addAll(btnScan, btnConnect, btnClearLog, autoScrollCheckBox);

        // 状态显示区
        VBox statusBox = new VBox(5);
        statusBox.setPadding(new Insets(10));
        statusBox.setStyle("-fx-border-color: #ccc; -fx-border-radius: 5; -fx-background-radius: 5;");

        lblConnStatus = new Label("连接状态：初始化中...");
        lblConnStatus.setStyle("-fx-font-weight: bold;");

        lblNeedAction = new Label("需要发送动作：—");
        lblLastAction = new Label("最近发送动作：—");

        statusBox.getChildren().addAll(lblConnStatus, lblNeedAction, lblLastAction);

        // 日志区域
        Label logLabel = new Label("通信日志：");
        logLabel.setStyle("-fx-font-weight: bold;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        // 组装界面
        root.getChildren().addAll(topBox, controlBox, statusBox, logLabel, logArea);

        Scene scene = new Scene(root, 650, 550);
        primaryStage.setScene(scene);
        primaryStage.show();

        // 启动Python进程
        startPythonProcess();

        // 启动定时任务
        startRandomActionLoop();
        startUIUpdateLoop();

        // 关闭窗口时的清理
        primaryStage.setOnCloseRequest(e -> {
            shutdown();
            Platform.exit();
        });
    }

    /**
     * 启动Python BLE服务进程
     */
    private void startPythonProcess() {
        try {
            log("启动Python BLE服务...");

            // 检查Python和必要的包
            checkPythonEnvironment();

            // 获取Python脚本路径
            String scriptPath = getPythonScriptPath();

            // 构建命令
            ProcessBuilder pb = new ProcessBuilder("python", scriptPath);
            pb.redirectErrorStream(false);

            // 设置环境变量
            Map<String, String> env = pb.environment();
            env.put("PYTHONUNBUFFERED", "1"); // 禁用Python缓冲

            // 启动进程
            pythonProcess = pb.start();

            // 获取输入输出流
            pythonReader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()));
            pythonWriter = new BufferedWriter(new OutputStreamWriter(pythonProcess.getOutputStream()));

            // 启动读取线程
            startPythonReaderThread();

            // 获取错误流
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(pythonProcess.getErrorStream()));
            Thread errorThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        final String errorLine = line;
                        Platform.runLater(() -> log("[Python错误] " + errorLine));
                    }
                } catch (IOException e) {
                    // 忽略
                }
            });
            errorThread.setDaemon(true);
            errorThread.start();

        } catch (Exception e) {
            log("启动Python进程失败: " + e.getMessage());
            e.printStackTrace();

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("错误");
                alert.setHeaderText("无法启动BLE服务");
                alert.setContentText("请确保已安装Python和Bleak库。\n\n" +
                        "安装方法：pip install bleak\n\n" +
                        "错误详情：" + e.getMessage());
                alert.showAndWait();
            });
        }
    }

    /**
     * 检查Python环境
     */
    private void checkPythonEnvironment() throws IOException {
        // 检查Python版本
        Process p = Runtime.getRuntime().exec("python --version");
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String version = reader.readLine();
        log("Python版本: " + version);

        // 检查Bleak是否安装
        try {
            Process p2 = Runtime.getRuntime().exec("python -c \"import bleak; print(bleak.__version__)\"");
            BufferedReader reader2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
            String bleakVersion = reader2.readLine();
            log("Bleak版本: " + bleakVersion);
        } catch (Exception e) {
            log("警告: Bleak可能未安装，请运行: pip install bleak");
        }
    }

    /**
     * 获取Python脚本路径
     */
    private String getPythonScriptPath() {
        // 首先检查当前目录
        File scriptFile = new File("ble_service.py");
        if (scriptFile.exists()) {
            return scriptFile.getAbsolutePath();
        }

        // 检查resources目录
        scriptFile = new File("src/main/resources/ble_service.py");
        if (scriptFile.exists()) {
            return scriptFile.getAbsolutePath();
        }

        // 如果都不存在，创建一个
        log("Python脚本不存在，将创建默认脚本...");
        return "ble_service.py";
    }

    /**
     * 启动Python输出读取线程
     */
    private void startPythonReaderThread() {
        pythonReaderThread = new Thread(() -> {
            try {
                String line;
                while ((line = pythonReader.readLine()) != null) {
                    handlePythonMessage(line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() -> log("Python进程通信中断: " + e.getMessage()));
                }
            }
        });
        pythonReaderThread.setDaemon(true);
        pythonReaderThread.start();
    }

    /**
     * 处理Python发来的消息
     */
    private void handlePythonMessage(String jsonMessage) {
        try {
            JsonObject message = JsonParser.parseString(jsonMessage).getAsJsonObject();
            String type = message.get("type").getAsString();

            Platform.runLater(() -> {
                switch (type) {
                    case "ready":
                        log("Python BLE服务已就绪");
                        isPythonReady.set(true);
                        btnConnect.setDisable(false);
                        btnScan.setDisable(false);
                        lblConnStatus.setText("连接状态：未连接");
                        break;

                    case "log":
                        String level = message.get("level").getAsString();
                        String msg = message.get("message").getAsString();
                        if ("TX".equals(level) || "RX".equals(level)) {
                            log("[" + level + "] " + msg);
                        } else {
                            log(msg);
                        }
                        break;

                    case "scan_result":
                        if (message.has("error")) {
                            log("扫描失败: " + message.get("error").getAsString());
                        } else {
                            boolean targetFound = message.get("target_found").getAsBoolean();
                            if (targetFound) {
                                String targetName = message.get("target_name").getAsString();
                                log("找到目标设备: " + targetName);
                                // 自动连接
                                connectToDevice(null);
                            } else {
                                log("未找到目标设备");
                            }
                        }
                        break;

                    case "connect_result":
                        boolean success = message.get("success").getAsBoolean();
                        if (success) {
                            isConnected.set(true);
                            lblConnStatus.setText("连接状态：已连接");
                            log("设备连接成功");
                        } else {
                            isConnected.set(false);
                            lblConnStatus.setText("连接状态：未连接");
                            String error = message.has("error") ? message.get("error").getAsString() : "未知错误";
                            log("连接失败: " + error);
                        }
                        break;

                    case "disconnect_result":
                        isConnected.set(false);
                        setPaused(false);  // [NEW] 断开时清除暂停状态
                        lblConnStatus.setText("连接状态：未连接");
                        log("已断开连接");
                        break;

                    case "notification":
                        String hexData = message.get("data").getAsString();
                        byte[] data = hexStringToBytes(hexData);
                        processReceivedData(data);
                        break;

                    case "connection_lost":
                        isConnected.set(false);
                        setPaused(false);  // [NEW]
                        lblConnStatus.setText("连接状态：连接丢失");
                        log("设备连接已丢失");
                        break;

                    case "send_result":
                        boolean sendSuccess = message.get("success").getAsBoolean();
                        if (!sendSuccess && message.has("error")) {
                            log("发送失败: " + message.get("error").getAsString());
                        }
                        break;

                    case "error":
                        log("错误: " + message.get("message").getAsString());
                        break;
                }
            });

        } catch (Exception e) {
            Platform.runLater(() -> log("解析Python消息失败: " + e.getMessage()));
        }
    }

    /**
     * 发送命令到Python进程
     */
    private void sendPythonCommand(JsonObject command) {
        if (!isPythonReady.get()) {
            log("Python服务未就绪");
            return;
        }

        try {
            String json = gson.toJson(command) + "\n";
            pythonWriter.write(json);
            pythonWriter.flush();
        } catch (IOException e) {
            log("发送命令失败: " + e.getMessage());
        }
    }

    /**
     * 扫描BLE设备
     */
    private void scanDevices() {
        log("开始扫描BLE设备...");
        lblConnStatus.setText("连接状态：扫描中...");

        JsonObject command = new JsonObject();
        command.addProperty("action", "scan");
        command.addProperty("timeout", 10.0);
        sendPythonCommand(command);
    }

    /**
     * 连接到设备
     */
    private void connectToDevice(String address) {
        log("正在连接设备...");
        lblConnStatus.setText("连接状态：连接中...");

        JsonObject command = new JsonObject();
        command.addProperty("action", "connect");
        if (address != null) {
            command.addProperty("address", address);
        }
        sendPythonCommand(command);
    }

    /**
     * 断开连接
     */
    private void disconnect() {
        log("断开连接...");

        JsonObject command = new JsonObject();
        command.addProperty("action", "disconnect");
        sendPythonCommand(command);

        isConnected.set(false);
        setPaused(false);  // [NEW]
    }

    /**
     * 处理连接按钮点击
     */
    private void handleConnectButton() {
        if (isConnected.get()) {
            disconnect();
        } else {
            scanDevices();
        }
    }

    /**
     * [NEW] 发送恢复App控制命令
     */
    private void sendResumeAppControl() {
        if (!isConnected.get()) {
            log("设备未连接，无法发送恢复命令");
            return;
        }

        log("发送恢复App控制命令...");
        byte[] frame = buildResumeFrame();
        sendBLEData(frame);
    }

    /**
     * [NEW] 设置暂停状态
     */
    private void setPaused(boolean paused) {
        pausedByLocal = paused;
        Platform.runLater(() -> {
            btnResume.setVisible(paused);
            if (paused) {
                log("App操作已暂停（本地按键优先）");
            } else {
                log("App控制已恢复");
            }
        });
    }

    /**
     * 发送数据到BLE设备
     */
    private void sendBLEData(byte[] data) {
        if (!isConnected.get()) {
            return;
        }

        String hexData = bytesToHexString(data);

        JsonObject command = new JsonObject();
        command.addProperty("action", "send");
        command.addProperty("data", hexData);
        sendPythonCommand(command);
    }

    /**
     * 处理接收到的数据
     */
    private void processReceivedData(byte[] data) {
        synchronized (receiveBuffer) {
            receiveBuffer.write(data, 0, data.length);

            // 尝试解析帧
            while (true) {
                byte[] buffer = receiveBuffer.toByteArray();
                if (buffer.length < 9) {
                    break;
                }

                // 查找帧头
                int frameStart = -1;
                for (int i = 0; i <= buffer.length - 2; i++) {
                    if (buffer[i] == (byte)0xAA && buffer[i + 1] == (byte)0x55) {
                        frameStart = i;
                        break;
                    }
                }

                if (frameStart == -1) {
                    receiveBuffer.reset();
                    break;
                }

                if (frameStart > 0) {
                    receiveBuffer.reset();
                    receiveBuffer.write(buffer, frameStart, buffer.length - frameStart);
                    buffer = receiveBuffer.toByteArray();
                }

                if (buffer.length < 9) {
                    break;
                }

                // 解析长度
                int len = (buffer[5] & 0xFF) | ((buffer[6] & 0xFF) << 8);
                int frameLength = 9 + len;

                if (buffer.length < frameLength) {
                    break;
                }

                // 提取完整帧
                byte[] frame = Arrays.copyOfRange(buffer, 0, frameLength);
                parseIncomingFrame(frame);

                // 移除已处理的帧
                receiveBuffer.reset();
                if (buffer.length > frameLength) {
                    receiveBuffer.write(buffer, frameLength, buffer.length - frameLength);
                }
            }
        }
    }

    /**
     * 解析接收到的帧
     */
    private void parseIncomingFrame(byte[] frame) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

            // SOF
            byte sof0 = bb.get(), sof1 = bb.get();
            if (sof0 != (byte)0xAA || sof1 != (byte)0x55) {
                return;
            }

            byte ver = bb.get();
            byte cmd = bb.get();
            byte rseq = bb.get();
            short len = bb.getShort();

            if (len < 0 || len > 512) {
                return;
            }

            byte[] payload = new byte[len];
            bb.get(payload);

            short receivedCrc = bb.getShort();

            // 验证CRC
            ByteBuffer crcData = ByteBuffer.allocate(5 + len).order(ByteOrder.LITTLE_ENDIAN);
            crcData.put(ver).put(cmd).put(rseq).putShort(len).put(payload);
            int calculatedCrc = calculateCRC16(crcData.array());

            if ((receivedCrc & 0xFFFF) != calculatedCrc) {
                log("CRC校验失败");
                return;
            }

            // 处理命令
            if (cmd == (byte)(0x80 + CMD_SET_PATTERN) ||
                    cmd == (byte)(0x80 + CMD_STOP_ALL) ||
                    cmd == (byte)(0x80 + CMD_QUERY_STATE)) {
                // ACK
                if (payload.length >= 2) {
                    byte ackSeq = payload[0];
                    byte status = payload[1];
                    log(String.format("收到ACK: CMD=0x%02X SEQ=%d STATUS=%d",
                            cmd & 0xFF, ackSeq & 0xFF, status & 0xFF));

                    // [NEW] 锁定期：ACK=BUSY -> 进入暂停并提示
                    if (status == 1) { // BUSY
                        setPaused(true);
                    }
                }
            } else if (cmd == (byte)(0x80 + CMD_RESUME_APP)) {
                // [NEW] 恢复命令ACK
                if (payload.length >= 2) {
                    byte status = payload[1];
                    if (status == 0) { // OK
                        setPaused(false);
                    }
                }
            } else if (cmd == CMD_STATE_RPT) {
                log("收到状态报告，长度=" + payload.length);
                parseStateReport(payload);  // [NEW]
            } else {
                log(String.format("收到命令: 0x%02X 长度=%d", cmd & 0xFF, len));
            }

        } catch (Exception e) {
            log("解析帧失败: " + e.getMessage());
        }
    }

    /**
     * [NEW] 解析StateReport扩展字段
     */
    private void parseStateReport(byte[] p) {
        try {
            int off = 0;
            if (p.length < 12) return;

            // 跳过基础字段
            off += 2; // FW_VER
            off += 2; // BAT_mV
            off += 2; // TEMP_dC
            off += 1; // CH_CNT
            off += 1; // CUR_PATTERN
            off += 1; // CUR_INTLVL
            off += 2; // RUN_REMAIN
            off += 1; // FLAGS

            // 扩展字段（至少9字节）
            if (p.length >= off + 9) {
                int rev = ((p[off] & 0xFF) | ((p[off+1] & 0xFF) << 8));
                off += 2;

                int src = (p[off++] & 0xFF);
                int chgMask = (p[off++] & 0xFF);
                int owner = (p[off++] & 0xFF);
                int holdMode = (p[off++] & 0xFF);
                int holdTtl = ((p[off] & 0xFF) | ((p[off+1] & 0xFF) << 8));
                off += 2;
                int btnCode = (p[off++] & 0xFF);

                log(String.format("StateReport扩展: src=%d owner=%d holdMode=%d holdTtl=%d",
                        src, owner, holdMode, holdTtl));

                // 根据状态更新暂停标志
                if (src == SRC_BUTTON && holdMode == HOLD_MANUAL) {
                    setPaused(true);
                }
                if (holdMode == HOLD_NONE) {
                    setPaused(false);
                }
            }
        } catch (Exception e) {
            log("解析StateReport失败: " + e.getMessage());
        }
    }

    /**
     * 构建动作对应的帧（与Android版本相同）
     */
    private byte[] buildFrameForAction(String action) {
        switch (action) {
            case "001":
                return buildSetPatternFrame(PATTERN_1, LEVEL_L, 2000, (byte)0);
            case "002":
                return buildSetPatternFrame(PATTERN_2, LEVEL_M, 2000, (byte)0);
            case "003":
                return buildSetPatternFrame(PATTERN_3, LEVEL_M, 0, (byte)1);
            case "004":
                return buildStopAllFrame();
            default:
                return null;
        }
    }

    private byte[] buildSetPatternFrame(byte patternId, byte intLevel, int durationMs, byte flags) {
        ByteBuffer payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(patternId);
        payload.put(intLevel);
        payload.putShort((short)durationMs);
        payload.put(flags);

        return buildFrame(CMD_SET_PATTERN, payload.array());
    }

    private byte[] buildStopAllFrame() {
        return buildFrame(CMD_STOP_ALL, new byte[0]);
    }

    // [NEW] 构建恢复控制帧
    private byte[] buildResumeFrame() {
        return buildFrame(CMD_RESUME_APP, new byte[0]);
    }

    private byte[] buildFrame(byte cmd, byte[] payload) {
        int len = payload.length;
        ByteBuffer frame = ByteBuffer.allocate(9 + len).order(ByteOrder.LITTLE_ENDIAN);

        // SOF
        frame.put((byte)0xAA).put((byte)0x55);
        // VER
        frame.put(VER);
        // CMD
        frame.put(cmd);
        // SEQ
        frame.put(seq);
        // LEN
        frame.putShort((short)len);
        // PAYLOAD
        frame.put(payload);

        // CRC
        ByteBuffer crcData = ByteBuffer.allocate(5 + len).order(ByteOrder.LITTLE_ENDIAN);
        crcData.put(VER).put(cmd).put(seq).putShort((short)len).put(payload);
        int crc = calculateCRC16(crcData.array());
        frame.putShort((short)crc);

        seq++;

        return frame.array();
    }

    /**
     * CRC16-CCITT计算（与Android版本相同）
     */
    private int calculateCRC16(byte[] data) {
        int crc = 0xFFFF;

        for (byte b : data) {
            crc ^= ((b & 0xFF) << 8);

            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }

        return crc;
    }

    /**
     * 启动随机动作生成任务（与Android版本相同）
     */
    private void startRandomActionLoop() {
        randomActionTask = scheduler.scheduleAtFixedRate(() -> {
            String[] actions = {"001", "002", "003", "004"};
            nextAction = actions[random.nextInt(actions.length)];

            // [MODIFIED] 仅在已连接且未暂停时发送
            if (isConnected.get() && !pausedByLocal) {
                byte[] frame = buildFrameForAction(nextAction);
                if (frame != null) {
                    sendBLEData(frame);
                    lastAction = nextAction;
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 启动UI更新任务
     */
    private void startUIUpdateLoop() {
        uiUpdateTask = scheduler.scheduleAtFixedRate(() -> {
            Platform.runLater(() -> {
                lblNeedAction.setText("需要发送动作：" + nextAction);
                lblLastAction.setText("最近发送动作：" + lastAction);
                // [NEW] 根据暂停状态更新UI
                if (pausedByLocal) {
                    lblConnStatus.setText("连接状态：已连接（暂停中）");
                } else if (isConnected.get()) {
                    lblConnStatus.setText("连接状态：已连接");
                }
            });
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录日志
     */
    private void log(String message) {
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String logLine = "[" + timestamp + "] " + message + "\n";

        Platform.runLater(() -> {
            logArea.appendText(logLine);

            // 自动滚动到底部
            if (autoScrollCheckBox.isSelected()) {
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 十六进制字符串转字节数组
     */
    private byte[] hexStringToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * 关闭应用时的清理
     */
    private void shutdown() {
        log("正在关闭应用...");

        // 停止定时任务
        if (randomActionTask != null) {
            randomActionTask.cancel(true);
        }
        if (uiUpdateTask != null) {
            uiUpdateTask.cancel(true);
        }
        scheduler.shutdownNow();

        // 断开BLE连接
        if (isConnected.get()) {
            disconnect();
        }

        // 发送退出命令给Python
        if (isPythonReady.get()) {
            JsonObject command = new JsonObject();
            command.addProperty("action", "exit");
            sendPythonCommand(command);
        }

        // 等待Python进程结束
        if (pythonProcess != null) {
            try {
                pythonProcess.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 忽略
            }
            pythonProcess.destroyForcibly();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}