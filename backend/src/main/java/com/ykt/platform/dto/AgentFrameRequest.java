package com.ykt.platform.dto;

/** Agent 回传的实时页面截图（dataURL），供控制台「浏览器模式」直接查看雨课堂原页面。 */
public record AgentFrameRequest(String image, String url) {
}
