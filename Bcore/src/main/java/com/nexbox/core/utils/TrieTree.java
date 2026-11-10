package com.nexbox.core.utils;

import java.util.Arrays;
import java.util.List;

/** Path prefixes: readers allocate nothing; registration publishes new child arrays. */
public final class TrieTree {
    private static final TrieNode[] NO_CHILDREN = new TrieNode[0];
    private final TrieNode root = new TrieNode('\0');

    
    private static final class TrieNode {
        final char content;
        volatile String word;
        volatile TrieNode[] children = NO_CHILDREN;

        TrieNode(char content) {
            this.content = content;
        }


        public TrieNode nextNode(char content) {
            for (TrieNode childNode : children) {
                if (childNode.content == content)
                    return childNode;
            }
            return null;
        }
    }

    public synchronized void add(String word) {
        TrieNode current = root;
        for (int index = 0; index < word.length(); ++index) {
            char content = word.charAt(index);
            TrieNode child = current.nextNode(content);
            if (child == null) {
                child = new TrieNode(content);
                TrieNode[] children = current.children;
                TrieNode[] expanded = Arrays.copyOf(children, children.length + 1);
                expanded[children.length] = child;
                current.children = expanded;
            }
            current = child;
        }
        if (!word.isEmpty()) current.word = word;
    }

    public void addAll(List<String> words) {
        for (String word : words) {
            add(word);
        }
    }

    public String search(String path) {
        TrieNode current = root;
        String match = null;
        for (int index = 0; index < path.length(); ++index) {
            current = current.nextNode(path.charAt(index));
            if (current == null) break;
            String word = current.word;
            if (word != null && (path.charAt(index) == '/' || index + 1 == path.length()
                    || path.charAt(index + 1) == '/')) {
                match = word;
            }
        }
        return match;
    }
}
