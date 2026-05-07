package com.easycode.base.dto.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResp<T> {
    @Schema(description = "分页数据列表")
    private List<T> list;
    @Schema(description = "分页数据")
    private PageVO page;
    
    public static <T> PageResp<T> from(IPage<T> page){
        PageResp<T> pageResp = new PageResp<>();
        pageResp.setList(page.getRecords());
        pageResp.setPage(toNewPage(page));
        return pageResp;
    }
    
    private static PageVO toNewPage(IPage<?> page) {
        PageVO tmpPage = new PageVO();
        tmpPage.setPageCount(page.getPages());
        tmpPage.setPageSize(page.getSize());
        tmpPage.setPageNo(page.getCurrent());
        tmpPage.setTotalRecord(page.getTotal());
        return tmpPage;
    }
    
    /**
     * 列表的对象要做类型转换
     * @param <T>
     * @param <O>
     * @param page
     * @param clazz
     * @return
     */
    public static <T, O> PageResp<T> from(IPage<O> page, Class<T> clazz){
        PageResp<T> pageResp = new PageResp<>();
        pageResp.setList(BeanUtil.copyToList(page.getRecords(), clazz));
        pageResp.setPage(toNewPage(page));
        return pageResp;
    }

    public static <T, O> PageResp<T> from(IPage<O> page, BeanConvertor<O, T> convertor){
        PageResp<T> pageResp = new PageResp<>();
        if(CollectionUtils.isNotEmpty(page.getRecords())) {
            pageResp.setList(page.getRecords().stream().map(convertor::convert).collect(Collectors.toList()));
        } else {
            pageResp.setList(new ArrayList<>());
        }
        pageResp.setPage(toNewPage(page));
        return pageResp;
    }
    
    public static <T, O> PageResp<T> from(IPage<O> page, Class<T> clazz, String ...ignoreFields){
        PageResp<T> pageResp = new PageResp<>();
        CopyOptions opt = new CopyOptions();
        opt.setIgnoreProperties(ignoreFields);
        pageResp.setList(BeanUtil.copyToList(page.getRecords(), clazz, opt));
        pageResp.setPage(toNewPage(page));
        return pageResp;
    }
    
}
