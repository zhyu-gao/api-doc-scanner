package com.easycode.base.dto.api;

public interface BeanConvertor<O, T> {
    public T convert(O src);
}
