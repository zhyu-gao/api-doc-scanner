package com.easycode.base.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * @ClassName ErrorData
 * @Description 错误数据
 * @Author hp
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ErrorData {
    @Schema(description = "错误编码")
    private String code;
    @Schema(description = "错误信息")
    private String message;
    @Schema(description = "错误详情")
    private Object data;
    
    public ErrorData(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public ErrorData(Object data) {
        this.data = data;
    }
    
    public static ErrorData from(String code, String message) {
        return new ErrorData(code, message);
    }
    
    public static ErrorData from(Object data) {
        if(data instanceof ErrorData) {
            return (ErrorData) data;
        }
        return new ErrorData(data);
    }
    
    public static ErrorData from(String code, String message, Object data) {
        return new ErrorData(code, message, data);
    }
}
