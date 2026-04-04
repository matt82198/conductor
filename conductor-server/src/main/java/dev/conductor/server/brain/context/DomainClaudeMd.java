package dev.conductor.server.brain.context;

/**
 * Contents of a single CLAUDE.md file within a project, tied to a specific domain.
 *
 * @param relativePath relative path from the project root (e.g., "conductor-server/src/.../queue/CLAUDE.md")
 * @param content      the full text content of the CLAUDE.md file
 * @param domainName   domain name extracted from the first heading (e.g., "queue/")
 */
public record DomainClaudeMd(
        String relativePath,
        String content,
        String domainName
) {

    public DomainClaudeMd {
        if (relativePath == null) {
            relativePath = "";
        }
        if (content == null) {
            content = "";
        }
        if (domainName == null) {
            domainName = "unknown";
        }
    }
}
