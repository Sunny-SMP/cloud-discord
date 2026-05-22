//
// MIT License
//
// Copyright (c) 2024 Incendo
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package org.incendo.cloud.discord.jda6;

import io.leangen.geantyref.TypeToken;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.discord.slash.CommandScope;
import org.incendo.cloud.discord.slash.DiscordSetting;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.setting.Configurable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command manager for JDA6.
 *
 * @param <C> command sender type
 * @since 1.0.0
 */
@API(status = API.Status.STABLE, since = "1.0.0")
public class JDA6CommandManager<C> extends CommandManager<C> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JDA6CommandManager.class);

    public static final CloudKey<JDAInteraction> CONTEXT_JDA_INTERACTION = CloudKey.of(
            "cloud:jda_interaction",
            JDAInteraction.class
    );
    public static final CloudKey<ReplySetting<?>> META_REPLY_SETTING = CloudKey.of(
            "cloud:reply_setting",
            new TypeToken<ReplySetting<?>>() {
            }
    );

    private final JDAInteraction.InteractionMapper<C> senderMapper;
    private final Configurable<DiscordSetting> discordSettings;

    private BiPredicate<C, String> permissionPredicate;
    private JDACommandFactory<C> commandFactory;

    /**
     * Creates a new command manager.
     *
     * @param executionCoordinator execution coordinator instance
     * @param senderMapper         mapper from {@link JDAInteraction} to {@link C}
     */
    public JDA6CommandManager(
            final @NonNull ExecutionCoordinator<C> executionCoordinator,
            final JDAInteraction.@NonNull InteractionMapper<C> senderMapper
    ) {
        super(executionCoordinator, CommandRegistrationHandler.nullCommandRegistrationHandler());
        this.commandFactory = new StandardJDACommandFactory<>(this.commandTree());
        this.discordSettings = Configurable.enumConfigurable(DiscordSetting.class);
        this.permissionPredicate = (sender, permission) -> true;
//        this.permissionPredicate = this::checkJdaPermission;
        this.senderMapper = Objects.requireNonNull(senderMapper, "senderMapper");
        this.registerCommandPostProcessor(new ReplyCommandPostprocessor<>(this));

        this.discordSettings.set(DiscordSetting.AUTO_REGISTER_SLASH_COMMANDS, true);
        this.registerDefaultExceptionHandlers();

        this.parserRegistry()
                .registerParser(JDAParser.userParser())
                .registerParser(JDAParser.roleParser())
                .registerParser(JDAParser.channelParser())
                .registerParser(JDAParser.mentionableParser())
                .registerParser(JDAParser.attachmentParser());

        // Common parameter injections.
        this.parameterInjectorRegistry().registerInjector(
                JDAInteraction.class,
                (ctx, annotations) -> ctx.get(CONTEXT_JDA_INTERACTION)
        );
        this.parameterInjectorRegistry().registerInjector(
                User.class,
                (ctx, annotations) -> ctx.get(CONTEXT_JDA_INTERACTION).user()
        );
        this.parameterInjectorRegistry().registerInjector(
                Member.class,
                (ctx, annotations) -> {
                    final JDAInteraction jdaInteraction = ctx.get(CONTEXT_JDA_INTERACTION);
                    final GenericCommandInteractionEvent event = jdaInteraction.interactionEvent();
                    if (event == null) {
                        return null;
                    }
                    return event.getMember();
                }
        );
        this.parameterInjectorRegistry().registerInjector(
                Guild.class,
                (ctx, annotations) -> ctx.get(CONTEXT_JDA_INTERACTION).guild()
        );
        this.parameterInjectorRegistry().registerInjector(
                Channel.class,
                (ctx, annotations) -> {
                    final JDAInteraction jdaInteraction = ctx.get(CONTEXT_JDA_INTERACTION);
                    final GenericCommandInteractionEvent event = jdaInteraction.interactionEvent();
                    if (event == null) {
                        return null;
                    }
                    return event.getChannel();
                }
        );
        this.parameterInjectorRegistry().registerInjector(
                JDA.class,
                (ctx, annotations) -> ctx.get(CONTEXT_JDA_INTERACTION).user().getJDA()
        );
    }

    @Override
    public boolean hasPermission(final @NonNull C sender, final @NonNull String permission) {
        return this.permissionPredicate.test(sender, permission);
    }

    /**
     * Returns the command factory.
     *
     * @return the command factory
     */
    public final @NonNull JDACommandFactory<C> commandFactory() {
        return this.commandFactory;
    }

    /**
     * Sets the command factory.
     *
     * @param commandFactory command factory
     */
    public final void commandFactory(final @NonNull JDACommandFactory<C> commandFactory) {
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
    }

    /**
     * Returns the mapper that maps from the Discord {@link User} to the sender of type {@link C}.
     *
     * @return the sender mapper
     */
    public final JDAInteraction.@NonNull InteractionMapper<C> senderMapper() {
        return this.senderMapper;
    }

    /**
     * Creates an event listener.
     *
     * @return the listener
     */
    public final @NonNull EventListener createListener() {
        return new CommandListener<>(this);
    }

    /**
     * Returns the Discord settings.
     *
     * @return discord settings
     */
    public final @NonNull Configurable<DiscordSetting> discordSettings() {
        return this.discordSettings;
    }

    /**
     * Sets the permission predicate.
     *
     * @param permissionPredicate permission predicate
     */
    public final void permissionPredicate(final @NonNull BiPredicate<C, String> permissionPredicate) {
        this.permissionPredicate = Objects.requireNonNull(permissionPredicate, "permissionPredicate");
    }

    /**
     * Registers global commands.
     *
     * @param jda JDA instance
     */
    public void registerGlobalCommands(final @NonNull JDA jda) {
        Objects.requireNonNull(jda, "jda");
        jda.updateCommands()
                .addCommands(this.commandFactory.createCommands(CommandScope.global()))
                .queue();
    }

    /**
     * Registers guild commands.
     *
     * @param guild guild to register commands to
     */
    public void registerGuildCommands(final @NonNull Guild guild) {
        Objects.requireNonNull(guild, "guild");

        try {
            LOGGER.info("Registering commands for guild {}", guild.getId());
            guild.updateCommands()
                    .addCommands(this.commandFactory.createCommands(CommandScope.guilds(guild.getIdLong())))
                    .queue(
                            success -> LOGGER.info("Registered guild commands for {}", guild.getName()),
                            error -> LOGGER.error(
                                    "Failed to register guild commands for {} ({})",
                                    guild.getName(),
                                    guild.getId(),
                                    error
                            )
                    );
        } catch (final Exception exception) {
            LOGGER.error("An error occurred while registering guild commands", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerDefaultExceptionHandlers() {
        final BiConsumer<CommandContext<C>, String> sendMessage = (context, message) -> {
            final JDAInteraction interaction = context.get(CONTEXT_JDA_INTERACTION);
            final ReplySetting<C> replySetting = (ReplySetting<C>) context
                    .getOrDefault(META_REPLY_SETTING, null);

            if (replySetting == null && this.discordSettings().get(DiscordSetting.EPHEMERAL_ERROR_MESSAGES)) {
                interaction.replyCallback().deferReply(true).queue();
                interaction.interactionEvent().getHook().sendMessage(message).queue();
            } else if (replySetting != null && replySetting.defer()) {
                interaction.interactionEvent().getHook().sendMessage(message).queue();
            } else {
                interaction.replyCallback().reply(message).queue();
            }
        };

        this.registerDefaultExceptionHandlers(
                triplet -> {
                    final CommandContext<C> context = triplet.first();
                    final JDAInteraction interaction = context.get(CONTEXT_JDA_INTERACTION);
                    final ReplySetting<C> replySetting = (ReplySetting<C>) context
                            .getOrDefault(META_REPLY_SETTING, null);

                    final String message = context.formatCaption(triplet.second(), triplet.third());
                    if (replySetting == null && this.discordSettings().get(DiscordSetting.EPHEMERAL_ERROR_MESSAGES)) {
                        interaction.replyCallback().deferReply(true).queue();
                        interaction.interactionEvent().getHook().sendMessage(message).queue();
                    } else if (replySetting != null && replySetting.defer()) {
                        interaction.interactionEvent().getHook().sendMessage(message).queue();
                    } else {
                        interaction.replyCallback().reply(message).queue();
                    }
                },
                pair -> LOGGER.error(pair.first(), pair.second())
        );
    }

    /**
     * Checks whether the sender has the given permission.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>No permission required ({@code permission} is blank or {@code null}) → allow.</li>
     *   <li>Sender is not a {@link JDAInteraction} (e.g. a console sender) → deny.</li>
     *   <li>DM context (no guild or no event) → deny; commands with permission
     *       requirements must be used inside a guild.</li>
     *   <li>No {@link Member} resolved from the event → deny.</li>
     *   <li>Guild owner → always allow.</li>
     *   <li>Permission string does not match any {@link Permission} constant → deny
     *       and log a warning.</li>
     *   <li>Member holds the required {@link Permission} → allow.</li>
     * </ol>
     *
     * <p>The permission string must match a {@link Permission} name
     * (e.g. {@code "MANAGE_ROLES"}), otherwise the check denies access.
     * Commands are annotated with {@link org.incendo.cloud.discord.jda6.annotation.JDAPermission}
     * using these names.
     *
     * @param sender     the command sender
     * @param permission the required JDA permission name, or blank/null if none is required
     * @return {@code true} if the sender is permitted to execute the command
     */
    private boolean checkJdaPermission(final C sender, final String permission) {
        if (permission == null || permission.isEmpty()) {
            return true;
        }

        if (!(sender instanceof JDAInteraction)) {
            return false;
        }

        final GenericCommandInteractionEvent event = ((JDAInteraction) sender).interactionEvent();
        if (event == null || !event.isFromGuild()) {
            return false;
        }

        final Member member = event.getMember();
        if (member == null) {
            return false;
        }

        if (member.isOwner()) {
            return true;
        }

        final Permission jdaPermission;
        try {
            jdaPermission = Permission.valueOf(permission.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            LOGGER.warn(
                    "Unknown JDA permission '{}' declared on a command — denying access by default.",
                    permission
            );
            return false;
        }

        return member.hasPermission(jdaPermission);
    }
}
