package com.interview.agent.resume.parser;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class TikaTextParser {
    private final Tika tika = new Tika();

    public String parse(InputStream in, String fileName) throws Exception {
        return tika.parseToString(in);
    }
}