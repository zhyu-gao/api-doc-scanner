package com.easycode.base.dto.api;

import com.easycode.base.dictionary.RespStatusEnum;
import com.easycode.base.util.MessageUtils;
import com.easycode.base.util.json.JSON;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * 所有的api接口返回对象基类
 * 
 * @author hp
 *
 * @param <T>
 */
@Data
public class Resp<T> {

    @Schema(description = "执行状态")
    private String message;
    @Schema(description = "状态码")
    private int status = 200;
    @Schema(description = "数据")
    private RespData<T> data;
    @Schema(description = "标识为Resp对象")
    private String v="Resp";
    @Schema(description = "标识为Resp版本")
    private String n="v1";
    
    public boolean isSuccess() {
    	return RespStatusEnum.Success.getValue() == status;
    }
    
    public int getCode() {
        return status;
    }
    
    /**
     * 初始化一个新创建的 Resp 对象，使其表示一个空消息。
     */
    public Resp() {
    }

    /**
     * 初始化一个新创建的 Resp 对象
     * 
     * @param status 状态码
     * @param msg    返回内容
     */
    public Resp(int status, String msg) {
        this.status = status;
        this.message = msg;
    }

    public Resp(int status, String msg, ErrorData error) {
        this.status = status;
        this.message = msg;
        this.data = RespData.fromError(error);
    }

    public Resp(int status, String msg, T result) {
        this.status = status;
        this.message = msg;
        this.data = RespData.from(result);
    }
    
    public Resp(int status, String msg, T result, Object error) {
        this.status = status;
        this.message = msg;
        this.data = RespData.from(result, ErrorData.from(error));
    }

    public Resp(int status, ErrorData error) {
        this.status = status;
        this.message = StringUtils.isNotEmpty(error.getMessage()) ? error.getMessage() : null;
        this.data = RespData.fromError(error);
    }

    /**
     * 初始化一个新创建的 Resp 对象
     * 
     * @param status 状态码
     * @param msg    返回内容
     */
    public Resp(T result) {
        this.status = RespStatusEnum.Success.getValue();
        this.data = RespData.from(result);
    }
    
    public Resp(RespStatusEnum en,  T result) {
        this.status = en.getValue();
        this.message = en.getLabel();
        this.data = RespData.from(result);
    }
    
    public Resp(RespStatusEnum en,  T result, ErrorData error) {
        this.status = en.getValue();
        this.message = en.getLabel();
        this.data = RespData.from(result, error);
    }

    /**
     * 返回成功消息
     * 
     * @return 成功消息
     */
    public static <T> Resp<T> success() {
        return Resp.successByMsg("操作成功");
    }

    /**
     * 返回成功数据
     * 
     * @return 成功消息
     */
    public static <T> Resp<T> successByData(T data) {
        return Resp.success("操作成功", data);
    }
    
    public static <T> Resp<T> okData(T data) {
    	return Resp.success("操作成功", data);
    }

    /**
     * 返回成功消息
     * 
     * @param msg 返回内容
     * @return 成功消息
     */
    public static <T> Resp<T> successByMsg(String msg) {
        return Resp.success(msg, null);
    }
    
    public static <T> Resp<T> okMsg(String msg) {
    	return Resp.success(msg, null);
    }
    
    public static <T> Resp<T> successByMsgCode(String msgCode) {
        return Resp.success(MessageUtils.getMessage(msgCode), null);
    }

    /**
     * 返回成功消息
     * 
     * @param msg  返回内容
     * @param data 数据对象
     * @return 成功消息
     */
    public static <T> Resp<T> success(String msg, T data) {
        return new Resp<T>(RespStatusEnum.Success.getValue(), msg, data);
    }

    /**
     * 返回错误消息
     * 
     * @return
     */
    public static <T> Resp<T> error() {
        return Resp.errorByMsg("操作失败");
    }

    public static <T> Resp<T> error(RespStatusEnum en) {
        return new Resp<T>(en, null);
    }
    
    public static <T> Resp<T> error(RespStatusEnum en, Object error) {
        return new Resp<T>(en, null, ErrorData.from(error));
    }
    
    public static <T> Resp<T> error(int errorType, String errorMsg) {
        return new Resp<T>(errorType, errorMsg);
    }

    /**
     * 返回错误消息
     * 
     * @param msg 返回内容
     * @return 警告消息
     */
    public static <T> Resp<T> errorByMsg(String msg) {
        return new Resp<T>(RespStatusEnum.SystemError.getValue(), msg);
    }
    
    public static <T> Resp<T> failMsg(String msg) {
    	return new Resp<T>(RespStatusEnum.SystemError.getValue(), msg);
    }
    
    public static <T> Resp<T> errorByMsgCode(String msgCode) {
        return new Resp<T>(RespStatusEnum.SystemError.getValue(), MessageUtils.getMessage(msgCode));
    }

    public static <T> Resp<T> errorByData(T error) {
        return new Resp<T>(RespStatusEnum.SystemError.getValue(), RespStatusEnum.SystemError.getLabel(), null, error);
    }

    public static <T> Resp<T> error(ErrorData error) {
        return new Resp<T>(RespStatusEnum.SystemError.getValue(), error);
    }

    /**
     * 返回错误消息
     * 
     * @param msg  返回内容
     * @param data 数据对象
     * @return 警告消息
     */
    public static <T> Resp<T> error(String msg, T data) {
        return new Resp<T>(RespStatusEnum.SystemError.getValue(), msg, data);
    }

    /**
     * 返回错误消息
     * 
     * @param status 状态码
     * @param msg    返回内容
     * @return 警告消息
     */
    public static <T> Resp<T> errorByMsg(int status, String msg) {
        return new Resp<T>(status, msg);
    }
    
    public static <T> Resp<T> errorByMsgCode(int status, String msgCode) {
        return new Resp<T>(status, MessageUtils.getMessage(msgCode));
    }
    
    public String toJson() {
        return JSON.toJSONString(this, false);
    }
}
