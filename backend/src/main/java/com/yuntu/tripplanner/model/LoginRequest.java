package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @JsonProperty("username")
    private String username;

    @NotBlank(message = "密码不能为空")
    @JsonProperty("password")
    private String password;
}
