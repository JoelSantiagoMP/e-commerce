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
public class TelegramCallbackQueryDTO {

    private String id;

    private TelegramChatDTO from;

    private TelegramMessageDTO message;

    private String data;
}
