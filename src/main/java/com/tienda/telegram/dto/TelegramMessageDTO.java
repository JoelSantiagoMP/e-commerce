package com.tienda.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelegramMessageDTO {

    @JsonProperty("message_id")
    private Long messageId;

    private TelegramChatDTO chat;

    private String text;
}
