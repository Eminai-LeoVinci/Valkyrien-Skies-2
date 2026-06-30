package org.valkyrienskies.mod.fabric.mixin.feature.vs_command_passthrough;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A SERVER-only {@code /vs} subcommand (e.g. {@code /vs teleport <ship> ~ ~ ~}) errors client-side and is
 * never sent to the server. VS2 (here) plus Eureka register a CLIENT-side {@code /vs} root
 * (expand-influence / contract-influence / cruise-cancel-debug), so Fabric's client dispatcher CLAIMS
 * {@code /vs teleport}, fails on the missing {@code teleport} child with a {@code dispatcherUnknownArgument}
 * exception, and does NOT forward that error type to the server (it forwards only
 * {@code dispatcherUnknownCommand}/{@code dispatcherParseException}).
 *
 * <p>We run BEFORE Fabric's HEAD inject (mixin priority 900 &lt; Fabric's default 1000, so our callback is
 * applied first at {@code @At("HEAD")}). If the typed command is a fully-valid SERVER command with no signable
 * arguments AND is NOT one the Fabric client dispatcher owns, we emit exactly the unsigned packet vanilla
 * {@code sendCommand} would and {@code cancel()} -- so Fabric's later callback (which would throw the client
 * error) never runs. The send goes straight out via {@code send(Packet)}, never re-entering {@code sendCommand},
 * so there is no loop. When we do NOT cancel, behavior is byte-for-byte as before.
 *
 * <p>The client-ownership check is REQUIRED: the sibling {@code command_suggestion_merge} mixin grafts
 * EXECUTABLE copies of the client {@code /vs} subcommands into the server-synced dispatcher (for tab-completion),
 * so a server-side parse alone cannot tell {@code expand-influence} (client) from {@code teleport} (server) --
 * both parse as fully valid. Walking the matched leading literals through the client tree disambiguates: a
 * client subcommand's literals all exist client-side; a server-only subcommand diverges (its literal is absent).
 */
@Mixin(value = ClientPacketListener.class, priority = 900)
public abstract class MixinClientPacketListenerPassthrough {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void vs$forwardServerOwnedCommand(final String command, final CallbackInfo ci) {
        final ClientPacketListener self = (ClientPacketListener) (Object) this;
        // 1.21.1: getCommands() is CommandDispatcher<SharedSuggestionProvider> (1.21.11 typed it as
        // ClientSuggestionProvider). getSuggestionsProvider() returns a ClientSuggestionProvider, which IS a
        // SharedSuggestionProvider, so it parses fine against this dispatcher.
        final CommandDispatcher<SharedSuggestionProvider> serverDispatcher = self.getCommands();
        if (serverDispatcher == null) {
            return;
        }
        final ParseResults<SharedSuggestionProvider> parse =
            serverDispatcher.parse(command, self.getSuggestionsProvider());
        // Forward ONLY a command the server can fully run, that carries no signable args, and that the Fabric
        // client dispatcher does NOT own (the last check excludes our own client /vs subcommands, whose
        // executable tab-completion copies in the server tree otherwise make them look server-valid).
        if (!vs$isFullyValid(parse) || SignableCommand.hasSignableArguments(parse) || vs$clientOwns(parse)) {
            return;
        }
        self.send(new ServerboundChatCommandPacket(command));
        ci.cancel();
    }

    /** Mirror of vanilla {@code ClientPacketListener#isValidCommand}: fully consumed + reaches an executable node. */
    private static boolean vs$isFullyValid(final ParseResults<?> parse) {
        return !parse.getReader().canRead()
            && parse.getExceptions().isEmpty()
            && parse.getContext().getLastChild().getCommand() != null;
    }

    /**
     * True if the Fabric client dispatcher owns this command's leading subcommand literals (so it MUST run
     * client-side). Walks the matched server-side literal nodes through the client tree; any divergence (a
     * literal the client tree lacks, e.g. {@code teleport}) means the client does not own it.
     */
    private static boolean vs$clientOwns(final ParseResults<?> parse) {
        final CommandDispatcher<FabricClientCommandSource> client = ClientCommandInternals.getActiveDispatcher();
        if (client == null) {
            return false;
        }
        CommandNode<FabricClientCommandSource> node = client.getRoot();
        boolean walkedLiteral = false;
        for (final ParsedCommandNode<?> parsed : parse.getContext().getNodes()) {
            if (!(parsed.getNode() instanceof LiteralCommandNode)) {
                break; // stop at the first argument -- only subcommand literals identify ownership
            }
            final CommandNode<FabricClientCommandSource> child = node.getChild(parsed.getNode().getName());
            if (child == null) {
                return false; // client tree diverges -> client does not own this command
            }
            node = child;
            walkedLiteral = true;
        }
        return walkedLiteral;
    }
}
