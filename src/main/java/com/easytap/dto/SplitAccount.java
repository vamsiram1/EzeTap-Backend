package com.easytap.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SplitAccount {

    private String accountNumber;
    private String amount;
    private String ifsc;
}
