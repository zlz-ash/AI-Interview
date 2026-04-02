package com.ash.springai.interview_platform.common;

import lombok.Getter;
import com.ash.springai.interview_platform.exception.ErrorCode;

@Getter
public class Result<T> {
    private final int code;
    private final String message;
    private final T data;

    private Result(int code,String message,T data){
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(String message,T data){
        return new Result<>(200,message,data);
    }

    public static <T> Result<T> success(){
        return success(null,null);
    }

    public static <T> Result<T> success(T data){
        return success("success",data);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(CommonConstants.StatusCode.SERVER_ERROR, message, null);
    }
    
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
    
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
    
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public boolean isSuccess() {
        return CommonConstants.StatusCode.SUCCESS == this.code;
    }
}
