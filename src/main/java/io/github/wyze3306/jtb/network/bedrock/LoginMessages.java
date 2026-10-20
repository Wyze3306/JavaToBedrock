package io.github.wyze3306.jtb.network.bedrock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.protocol.bedrock.data.PlayStatus;

/**
 * Human readable kick messages for Bedrock login failures.
 */
final class LoginMessages {

    private LoginMessages() {
    }

    static Component playStatus(PlayStatus status) {
        String message = switch (status) {
            case LOGIN_FAILED_CLIENT_OLD -> "Le serveur Bedrock utilise une version plus récente que celle du proxy.";
            case LOGIN_FAILED_SERVER_OLD -> "Le serveur Bedrock utilise une version plus ancienne que celle du proxy.";
            case LOGIN_FAILED_INVALID_TENANT -> "Ce serveur Bedrock n'accepte pas ce compte (tenant invalide).";
            case LOGIN_FAILED_EDITION_MISMATCH_EDU_TO_VANILLA, LOGIN_FAILED_EDITION_MISMATCH_VANILLA_TO_EDU ->
                    "Édition incompatible (Education / Vanilla).";
            case LOGIN_FAILED_SERVER_FULL_SUB_CLIENT -> "Le serveur Bedrock est plein.";
            case LOGIN_FAILED_EDITOR_MISMATCH_EDITOR_TO_VANILLA, LOGIN_FAILED_EDITOR_MISMATCH_VANILLA_TO_EDITOR ->
                    "Mode éditeur incompatible.";
            default -> "Connexion refusée par le serveur Bedrock (" + status + ").";
        };
        return Component.text(message, NamedTextColor.RED);
    }
}
