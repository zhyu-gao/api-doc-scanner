package com.easycode.base.dto.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * api做分页查询时的参数
 * 
 * @author admin
 *
 */
@Data
public class PageReq implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "当前页码")
    protected Long pageNo;

    @Schema(description = "每页条数")
    protected Long pageSize;

    @Schema(description = "总条数，如果传该参数，將不会再统计总条数，速度更快")
    protected Long recordTotal;
    
    @Schema(description = "总页数")
    protected Long pageCount;

    public PageReq() {

    }

    public PageReq(Long pageNo, Long pageSize) {
        this.pageNo = pageNo;
        this.pageSize = pageSize;
    }
    
    public PageReq(Long pageNo, Long pageSize, Long totalRecord) {
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.recordTotal = totalRecord;
    }

    public <T> Page<T> toPage() {
        if (recordTotal == null || recordTotal == 0) {
            return new Page<T>(pageNo == null ? 1 : pageNo, pageSize == null ? 10 : pageSize);
        } else {
            return new Page<T>(pageNo == null ? 1 : pageNo, pageSize == null ? 10 : pageSize, recordTotal, false);
        }
    }

    public Long getPageSize() {
        if (pageSize == null || pageSize < 0) {
            pageSize = 10L;
        }
        return pageSize;
    }

    public Long getPageNo() {
        if (pageNo == null || pageNo <= 0) {
            pageNo = 1L;
        }
        return pageNo;
    }

    @Schema(description = "是否需要计算总数量", hidden = true)
    public boolean isNeedCount() {
        return recordTotal == null || recordTotal == 0;
    }
}