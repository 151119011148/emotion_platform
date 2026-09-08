package com.emotion.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StageAdviceVO {
    private String stage;
    private String tone;
    private String position;
    private String action;
    private String warning;
}
