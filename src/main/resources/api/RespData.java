package com.easycode.base.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 所有的api接口返回对象基类
 * 
 * @author hp
 *
 * @param <T>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RespData<T> {

    @Schema(description = "成功数据")
    private T result;
    @Schema(description = "失败数据")
    private ErrorData error;
    
    public static <T> RespData<T> from(T result){
        return new RespData<>(result, null);
    }
    
    public static <T> RespData<T> from(T result, ErrorData error){
        return new RespData<>(result, error);
    }

    public static <T> RespData<T> fromError(Object errorData){
        if(errorData instanceof ErrorData) {
            return from(null, (ErrorData)errorData);
        }
        return new RespData<>(null, ErrorData.from(errorData));
    }
}
