package com.algoeval.offline.verify;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 验证三件套专用测试应用：只扫描 offline 模块，H2 内存库 */
@SpringBootApplication(scanBasePackages = "com.algoeval.offline")
public class OfflineCalcTestApp {
}
