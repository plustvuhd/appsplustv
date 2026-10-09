package com.plustv.player;

/** Canal, filme ou série. */
public class Item {
    public String id = "", name = "", icon = "", ext = "", cat = "", kind = "";
    public Item() {}
    public Item(String kind, String id, String name, String icon, String ext) { this.kind = kind; this.id = id; this.name = name; this.icon = icon; this.ext = ext; }
}
