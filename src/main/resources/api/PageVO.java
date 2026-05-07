package com.easycode.base.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * api返回的分页信息
 * 
 * @author hp
 *
 * @param <T>
 */
@Data
public class PageVO  {
    @Schema(description = "当前页码")
    protected Long pageNo;

    @Schema(description = "每页条数")
    protected Long pageSize;

    @Schema(description = "总条数")
    protected Long totalRecord;
    
    @Schema(description = "总页数")
    protected Long pageCount;
    
    public Long getSize() {
        if(pageSize == null || pageSize <0) {
            pageSize = 10L;
        }
        return pageSize;
    }
    
    public Long getCurrent() {
        if(pageNo == null || pageNo<=0) {
            pageNo = 1L;
        }
        return pageNo;
    }
    
}
