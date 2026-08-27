package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度需在 3-50 之间")
    @JsonProperty("username")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 50, message = "密码长度需在 6-50 之间")
    @JsonProperty("password")
    private String password;

    @Size(max = 50, message = "昵称长度不能超过 50")
    @JsonProperty("nickname")
    private String nickname;
}
