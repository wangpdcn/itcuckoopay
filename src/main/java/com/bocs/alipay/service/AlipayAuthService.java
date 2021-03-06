package com.bocs.alipay.service;

import com.alipay.api.response.AlipayOpenAuthTokenAppResponse;
import com.bocs.alipay.model.builder.AlipayOpenAuthTokenAppRequestBuilder;

/**
 * Description:<p>第三方应用授权 </p>
 * Created by songqi on 2018/2/6.
 */
public interface AlipayAuthService {


    /**
     * 获取/更新第三方应用授权Token
     * @param builder
     * @return
     */
    AlipayOpenAuthTokenAppResponse openAuthTokenApp(AlipayOpenAuthTokenAppRequestBuilder builder);



}
