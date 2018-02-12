package com.bocs.alipay.trade.service.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradePayRequest;
import com.alipay.api.response.AlipayTradePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.bocs.alipay.trade.config.Constants;
import com.bocs.alipay.trade.model.TradeStatus;
import com.bocs.alipay.trade.model.builder.AlipayTradePayRequestBuilder;
import com.bocs.alipay.trade.model.builder.AlipayTradeQueryRequestBuilder;
import com.bocs.alipay.trade.model.result.AlipayF2FPayResult;
import com.bocs.alipay.trade.service.impl.hb.HbListener;
import com.bocs.alipay.trade.service.impl.hb.TradeListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;


/**
 * Created by liuyangkly on 15/7/29.
 *
 *  一定要在创建AlipayTradeService之前调用Configs.init("alipayrisk10");设置参数
 *
 */
@Service(value = "tradeService")
public class AlipayTradeWithHBServiceImpl extends AbsAlipayTradeService {

    @Autowired
    protected AlipayClient alipayClient ;


    /**
     * @PostConstruct是Java EE 5引入的注解，Spring允许开发者在受管Bean中使用它。
     * 当DI容器实例化当前受管Bean时，@PostConstruct注解的方法会被自动触发，从而完成一些初始化工作
     */
    @PostConstruct
    public void initAlipayClient(){
        super.alipayClient = alipayClient;
    }
    private TradeListener listener ;




    // 商户可以直接使用的pay方法，并且集成了监控代码
    @Override
    public AlipayF2FPayResult tradePay(AlipayTradePayRequestBuilder builder) {

        listener =  new HbListener();
        validateBuilder(builder);

        final String outTradeNo = builder.getOutTradeNo();

        AlipayTradePayRequest request = new AlipayTradePayRequest();
        // 设置平台参数
        request.setNotifyUrl(builder.getNotifyUrl());
        String appAuthToken = builder.getAppAuthToken();
        // todo 等支付宝sdk升级公共参数后使用如下方法
        // request.setAppAuthToken(appAuthToken);
        request.putOtherTextParam("app_auth_token", appAuthToken);

        // 设置业务参数
        request.setBizContent(builder.toJsonString());
        log.info("trade.pay request content:" + builder.toString());

        // 首先调用支付api
        final long beforeCall = System.currentTimeMillis();
        AlipayTradePayResponse response = getResponse(request, outTradeNo, beforeCall);

        AlipayF2FPayResult result = new AlipayF2FPayResult(response);
        if (response != null && Constants.SUCCESS.equals(response.getCode())) {
            // 支付交易明确成功
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    listener.onPayTradeSuccess(outTradeNo, beforeCall);
                }
            });
            result.setTradeStatus(TradeStatus.SUCCESS);
        } else if (response != null && Constants.PAYING.equals(response.getCode())) {
            // 返回支付中，同步交易耗时
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    listener.onPayInProgress(outTradeNo, beforeCall);
                }
            });

            // 返回用户处理中，则轮询查询交易是否成功，如果查询超时，则调用撤销
            AlipayTradeQueryRequestBuilder queryBuiler = new AlipayTradeQueryRequestBuilder()
                    .setAppAuthToken(appAuthToken)
                    .setOutTradeNo(outTradeNo);
            AlipayTradeQueryResponse loopQueryResponse = loopQueryResult(queryBuiler);
            return checkQueryAndCancel(outTradeNo, appAuthToken, result, loopQueryResponse);

        } else if (tradeError(response)) {
            // 系统错误，同步交易耗时
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    listener.onPayFailed(outTradeNo, beforeCall);
                }
            });

            // 系统错误，则查询一次交易，如果交易没有支付成功，则调用撤销
            AlipayTradeQueryRequestBuilder queryBuiler = new AlipayTradeQueryRequestBuilder()
                    .setAppAuthToken(appAuthToken)
                    .setOutTradeNo(outTradeNo);
            AlipayTradeQueryResponse queryResponse = tradeQuery(queryBuiler);
            return checkQueryAndCancel(outTradeNo, appAuthToken, result, queryResponse);

        } else {
            // 其他情况表明该订单支付明确失败
            result.setTradeStatus(TradeStatus.FAILED);

            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    listener.onPayFailed(outTradeNo, beforeCall);
                }
            });
        }

        return result;
    }





    private AlipayTradePayResponse getResponse(AlipayTradePayRequest request,
                                               final String outTradeNo, final long beforeCall) {
        try {
            AlipayTradePayResponse response = alipayClient.execute(request);
            if (response != null) {
                log.info(response.getBody());
            }
            return response;

        } catch (AlipayApiException e) {
            // 获取异常真实原因
            Throwable cause = e.getCause();

            if (cause instanceof ConnectException ||
                    cause instanceof NoRouteToHostException) {
                // 建立连接异常
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        listener.onConnectException(outTradeNo, beforeCall);
                    }
                });

            } else if (cause instanceof SocketException) {
                // 报文上送异常
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        listener.onSendException(outTradeNo, beforeCall);
                    }
                });

            } else if (cause instanceof SocketTimeoutException) {
                // 报文接收异常
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        listener.onReceiveException(outTradeNo, beforeCall);
                    }
                });
            }

            e.printStackTrace();
            return null;
        }
    }
}