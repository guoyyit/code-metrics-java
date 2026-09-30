package com.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Vector;
import java.util.Stack;
import java.util.Queue;
import java.util.Deque;

public class Smelly {

    public String greeting(String name, int age, boolean active, String city, String zip) {
        String result = "";
        if (active) {
            if (age > 10) {
                if (city != null) {
                    if (zip != null) {
                        result = "deep";
                    }
                }
            }
        }
        for (int i = 0; i < 10; i++) {
            result += i;
        }
        for (int i = 0; i < 10; i++) {
            result += i;
        }
        for (int i = 0; i < 10; i++) {
            result += i;
        }
        while (age > 0) {
            age--;
        }
        if (name == null) {
            result = "none";
        } else if (age == 1 || age == 2) {
            result = "some";
        }
        return result ? "x" : "y";
    }

    public void useTypes() {
        List<String> list = new ArrayList<>();
        Map<String, String> map = new HashMap<>();
        Map<String, String> linked = new LinkedHashMap<>();
        Map<String, String> tree = new TreeMap<>();
        HashSet<String> set = new HashSet<>();
        LinkedList<String> linkedList = new LinkedList<>();
        Vector<String> vector = new Vector<>();
        Stack<String> stack = new Stack<>();
        Queue<String> queue = null;
        Deque<String> deque = null;
        list.add("a");
        map.put("k", "v");
        linked.put("k", "v");
        tree.put("k", "v");
        set.add("s");
        linkedList.add("l");
        vector.add("v");
        stack.push("s");
        if (queue instanceof Object) {
            System.out.println(queue);
        }
    }
}
