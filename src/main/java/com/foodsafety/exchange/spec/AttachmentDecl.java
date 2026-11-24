package com.foodsafety.exchange.spec;

/** 随容器提交的附件声明（附件引用校验的依据）。 */
public record AttachmentDecl(String id, String sha256) {}
