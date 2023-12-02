package com.github.binarywang.demo.wx.mp.model;

import lombok.Data;

import java.util.List;

/**
 * @Name: FolderRes
 * @Desc:
 * @Author Liff
 * @Date 2022/5/19
 */
@Data
public class FolderRes {
    private String title;
    private List<Item> content;
    private String page_url;
    private String id;
    private String path;
    private String available_time;
    private String insert_time;

}
