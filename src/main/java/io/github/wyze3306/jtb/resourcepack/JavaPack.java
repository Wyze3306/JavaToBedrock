package io.github.wyze3306.jtb.resourcepack;

import java.util.UUID;

/**
 * A converted Java resource pack, ready to be served.
 *
 * @param id   stable id derived from the content (same packs, same id)
 * @param sha1 hex SHA-1 of {@code zip}, sent to the client for verification
 */
public record JavaPack(UUID id, String sha1, byte[] zip) {
}
