package com.foodsafety.exchange.spec;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 规范版本 -> 解析器注册表。 */
@Component
public class ParserRegistry {

  private final Map<SpecVersion, MessageParser> parsers = new EnumMap<>(SpecVersion.class);

  public ParserRegistry(List<MessageParser> parsers) {
    parsers.forEach(p -> this.parsers.put(p.version(), p));
  }

  public MessageParser parserFor(SpecVersion version) {
    return parsers.get(version);
  }
}
