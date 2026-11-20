package com.myjavaworld.jftp;

import com.myjavaworld.ftp.RemoteFile;

/** A directory entry with the configured parser's path and metadata semantics. */
public final class RemoteEntry {
    private final RemoteFile parsed;

    private RemoteEntry(RemoteFile parsed) {
        this.parsed = parsed;
    }

    static RemoteEntry wrap(RemoteFile file) {
        return file == null ? null : new RemoteEntry(file);
    }

    static RemoteFile unwrap(RemoteEntry entry) {
        return entry == null ? null : entry.parsed;
    }

    public String getName() {
        return parsed.getName();
    }

    public String getNormalizedName() {
        return parsed.getNormalizedName();
    }

    public String getPath() {
        return parsed.getPath();
    }

    public String getNormalizedPath() {
        return parsed.getNormalizedPath();
    }

    public String getExtension() {
        return parsed.getExtension();
    }

    public String getType() {
        return parsed.getType();
    }

    public long getSize() {
        return parsed.getSize();
    }

    public long getLastModified() {
        return parsed.getLastModified();
    }

    public boolean isDirectory() {
        return parsed.isDirectory();
    }

    public boolean isFile() {
        return parsed.isFile();
    }

    public boolean isLink() {
        return parsed.isLink();
    }

    public String getAttributes() {
        return parsed.getAttributes();
    }

    public void setAttributes(String attributes) {
        parsed.setAttributes(attributes);
    }

    public int getLinkCount() {
        return parsed.getLinkCount();
    }

    public String getOwner() {
        return parsed.getOwner();
    }

    public String getGroup() {
        return parsed.getGroup();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RemoteEntry entry && parsed.equals(entry.parsed);
    }

    @Override
    public int hashCode() {
        return parsed.hashCode();
    }

    @Override
    public String toString() {
        return parsed.toString();
    }
}
