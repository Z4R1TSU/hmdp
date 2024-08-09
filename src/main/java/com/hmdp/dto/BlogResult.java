package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class BlogResult {

    private List<?> list;
    private Long minTime;
    private Integer offset;

}
