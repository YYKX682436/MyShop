package com.myshop.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A historical snapshot retains its URL, but a newly rendered order should
 * not request a deleted managed file from an upstream proxy or browser cache.
 */
@Service
public class PublicImageResolver {
    private final PublicMediaVisibility publicMediaVisibility;
    private final ManagedMediaReferences paths;

    public PublicImageResolver(PublicMediaVisibility publicMediaVisibility, ManagedMediaReferences paths) {
        this.publicMediaVisibility = publicMediaVisibility;
        this.paths = paths;
    }

    public String visible(String url) {
        if (url == null) return null;
        String name = paths.managedPath(url);
        if (name == null) return url;
        return publicMediaVisibility.visible(name) ? url : null;
    }
}
