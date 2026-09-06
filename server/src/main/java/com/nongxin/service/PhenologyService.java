package com.nongxin.service;

import com.nongxin.model.PhenologyResult;

/**
 * 生育期推算接口（简化天数窗口模型）。
 */
public interface PhenologyService {

    PhenologyResult getPhenology(String crop, String sowDate);
}