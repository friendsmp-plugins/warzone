package com.centers25.warzone;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;

import java.time.Duration;

final class Msg {
    static final String BRAND = "gradient:#FF2E2E:#FF9F43";
    static final String RULE = "<#3A3F4B><st>" + " ".repeat(64) + "</st></#3A3F4B>";
    private static final String PREFIX = "<" + BRAND + "><b>⚔ WARZONE</b></gradient> <#4A4F5C>»</#4A4F5C> ";
    private static final MiniMessage MM = MiniMessage.builder().tags(TagResolver.resolver(TagResolver.standard(),
            style("hl", 0xFFC266), style("text", 0xE2E6EE), style("muted", 0x9AA3B2),
            style("dim", 0x4A4F5C), style("good", 0x5CF28A), style("bad", 0xFF4D4D))).build();

    private static TagResolver style(String name, int rgb) {
        return TagResolver.resolver(name, Tag.styling(TextColor.color(rgb)));
    }

    static Component parse(String text, TagResolver... tags) {
        return MM.deserialize(text, tags);
    }

    static void info(Audience to, String text, TagResolver... tags) {
        to.sendMessage(parse(PREFIX + "<text>" + text, tags));
    }

    static void success(Audience to, String text, TagResolver... tags) {
        to.sendMessage(parse(PREFIX + "<good>✔</good> <#B8F5CB>" + text, tags));
    }

    static void error(Audience to, String text, TagResolver... tags) {
        to.sendMessage(parse(PREFIX + "<bad>✖</bad> <#FFA3A3>" + text, tags));
    }

    static void actionBar(Audience to, String text, TagResolver... tags) {
        to.sendActionBar(parse(text, tags));
    }

    static void title(Audience to, String title, String subtitle, long stay, TagResolver... tags) {
        to.showTitle(Title.title(parse(title, tags), parse(subtitle, tags),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(stay), Duration.ofMillis(600))));
    }
}
