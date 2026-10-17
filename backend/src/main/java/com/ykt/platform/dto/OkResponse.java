package com.ykt.platform.dto;

public record OkResponse(boolean ok) {

    public static OkResponse of() {
        return new OkResponse(true);
    }
}
