package org.mineacademy.fo.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.PlayerUtil;
import org.mineacademy.fo.ReflectionUtil;
import org.mineacademy.fo.ReflectionUtil.MissingEnumException;
import org.mineacademy.fo.TabUtil;
import org.mineacademy.fo.Valid;
import org.mineacademy.fo.collection.StrictList;
import org.mineacademy.fo.command.placeholder.Placeholder;
import org.mineacademy.fo.command.placeholder.PositionPlaceholder;
import org.mineacademy.fo.exception.CommandException;
import org.mineacademy.fo.exception.FoException;
import org.mineacademy.fo.exception.InvalidCommandArgException;
import org.mineacademy.fo.model.ChatComponent;
import org.mineacademy.fo.model.Replacer;
import org.mineacademy.fo.plugin.SimplePlugin;
import org.mineacademy.fo.remain.Remain;
import org.mineacademy.fo.settings.SimpleLocalization;

import lombok.Getter;
import lombok.NonNull;

/**
 * A simple command used to replace all Bukkit/Spigot command functionality
 * across any plugin that utilizes this.
 */
public abstract class SimpleCommand extends Command {

	/**
	 * The default permission syntax for this command.
	 */
	protected static final String DEFAULT_PERMISSION_SYNTAX = "{plugin.name}.command.{label}";

	/**
	 * A list of placeholders to replace in this command, see {@link Placeholder}
	 *
	 * These are used when sending player messages
	 */
	private final StrictList<Placeholder> placeholders = new StrictList<>();

	/**
	 * The command label, eg. boss for /boss
	 *
	 * This variable is updated dynamically when the command is run with the
	 * last known version of the label, e.g. /boss or /b will set it to boss or b
	 * respectively
	 */
	private String label;

	/**
	 * Has this command been already registered?
	 */
	private boolean registered = false;

	/**
	 * Minimum arguments required to run this command
	 */
	@Getter
	private int minArguments = 0;

	// ----------------------------------------------------------------------
	// Temporary variables
	// ----------------------------------------------------------------------

	/**
	 * The command sender, or null if does not exist
	 *
	 * This variable is updated dynamically when the command is run with the
	 * last known sender
	 */
	protected CommandSender sender;

	/**
	 * The arguments used when the command was last executed
	 *
	 * This variable is updated dynamically when the command is run with the
	 * last known arguments
	 */
	protected String[] args;

	// ----------------------------------------------------------------------

	/**
	 * Create a new simple command with the given label.
	 *
	 * Separate the label with | to split between label and aliases.
	 * Example: remove|r|rm will create a /remove command that can
	 * also be run by typing /r and /rm as its aliases.
	 *
	 * @param label
	 */
	protected SimpleCommand(String label) {
		this(parseLabel0(label), parseAliases0(label));
	}

	/**
	 * Create a new simple command from the list. The first
	 * item in the list is the main label and the other ones are the aliases.
	 */
	protected SimpleCommand(StrictList<String> labels) {
		this(labels.get(0), labels.size() > 1 ? labels.range(1).getSource() : null);
	}

	/**
	 * Create a new simple command
	 *
	 * @param label
	 * @param aliases
	 */
	protected SimpleCommand(String label, List<String> aliases) {
		super(label);

		Valid.checkBoolean(!(this instanceof CommandExecutor), "Please do not write 'implements CommandExecutor' for /" + super.getLabel() + " cmd, we already have a listener there");
		Valid.checkBoolean(!(this instanceof TabCompleter), "Please do not write 'implements TabCompleter' for /" + super.getLabel() + " cmd, simply override tabComplete method");

		setLabel(label);

		if (aliases != null)
			setAliases(aliases);

		// Set a default permission for this command
		setPermission(DEFAULT_PERMISSION_SYNTAX);
	}

	/*
	 * Split the given label by | and get the first part, used as the main label
	 */
	private final static String parseLabel0(String label) {
		Valid.checkNotNull(label, "Label must not be null!");

		return label.split("\\|")[0];
	}

	/*
	 * Split the given label by | and use the second and further parts as aliases
	 */
	private final static List<String> parseAliases0(String label) {
		final String[] aliases = label.split("\\|");

		return aliases.length > 0 ? Arrays.asList(Arrays.copyOfRange(aliases, 1, aliases.length)) : new ArrayList<>();
	}

	// ----------------------------------------------------------------------
	// Registration
	// ----------------------------------------------------------------------

	/**
	 * Registers this command into Bukkit.
	 *
	 * Throws an error if the command {@link #isRegistered()} already.
	 */
	public final void register() {
		Valid.checkBoolean(!(this instanceof SimpleSubCommand), "Sub commands cannot be registered!");
		Valid.checkBoolean(!registered, "The command /" + getLabel() + " has already been registered!");

		final PluginCommand oldCommand = Bukkit.getPluginCommand(getLabel());

		if (oldCommand != null) {
			Common.log("&eCommand &f/" + getLabel() + " &eis already used by " + oldCommand.getPlugin().getName() + ", unregistering...");

			Remain.unregisterCommand(oldCommand.getLabel());
		}

		Remain.registerCommand(this);
		registered = true;
	}

	/**
	 * Removes the command from Bukkit.
	 *
	 * Throws an error if the command is not {@link #isRegistered()}.
	 */
	public final void unregister() {
		Valid.checkBoolean(!(this instanceof SimpleSubCommand), "Sub commands cannot be unregistered!");
		Valid.checkBoolean(registered, "The command /" + getLabel() + " is not registered!");

		Remain.unregisterCommand(getLabel());
		registered = false;
	}

	// ----------------------------------------------------------------------
	// Execution
	// ----------------------------------------------------------------------

	/**
	 * Execute this command, updates the {@link #sender}, {@link #label} and {@link #args} variables,
	 * checks permission and returns if the sender lacks it,
	 * checks minimum arguments and finally passes the command to the child class.
	 *
	 * Also contains various error handling scenarios
	 */
	@Override
	public final boolean execute(CommandSender sender, String label, String[] args) {

		// Set variables to re-use later
		this.sender = sender;
		this.label = label;
		this.args = args;

		// Catch "errors" that contain a message to send to the player
		try {

			// Check if sender has the proper permission
			if (getPermission() != null)
				checkPerm(getPermission());

			// Check for minimum required arguments and print help
			if (args.length < getMinArguments()) {

				// Enforce setUsage being used
				if (Common.getOrEmpty(getUsage()).isEmpty())
					throw new FoException("If you set getMinArguments you must also call setUsage for /" + getLabel() + " command!");

				if (!Common.getOrEmpty(getDescription()).isEmpty())
					Common.tell(sender, SimpleLocalization.Commands.LABEL_DESCRIPTION.replace("{description}", getDescription()));

				if (getMultilineUsageMessage() != null) {
					Common.tell(sender, SimpleLocalization.Commands.LABEL_USAGES);
					Common.tell(sender, getMultilineUsageMessage());

				} else {
					if (getMultilineUsageMessage() != null) {
						Common.tell(sender, SimpleLocalization.Commands.LABEL_USAGES);
						Common.tell(sender, getMultilineUsageMessage());

					} else {
						final String sublabel = this instanceof SimpleSubCommand ? " " + ((SimpleSubCommand) this).getSublabel() : "";

						Common.tell(sender, SimpleLocalization.Commands.LABEL_USAGE + " /" + label + sublabel + (!getUsage().startsWith("/") ? " " + Common.stripColors(getUsage()) : ""));
					}
				}

				return true;
			}

			onCommand();

		} catch (final InvalidCommandArgException ex) {
			if (getMultilineUsageMessage() == null)
				Common.tell(sender, ex.getMessage() != null ? ex.getMessage() : replacePlaceholders(SimpleLocalization.Commands.INVALID_SUB_ARGUMENT));
			else {
				Common.tell(sender, SimpleLocalization.Commands.INVALID_ARGUMENT_MULTILINE);
				Common.tell(sender, getMultilineUsageMessage());
			}

		} catch (final CommandException ex) {
			if (ex.getMessages() != null) {
				final boolean tellPrefix = Common.ADD_TELL_PREFIX;

				if (ex.getMessages().length > 2 || !addTellPrefix(args))
					Common.ADD_TELL_PREFIX = false;

				Common.tell(sender, replacePlaceholders(ex.getMessages()));
				Common.ADD_TELL_PREFIX = tellPrefix;
			}

		} catch (final Throwable t) {
			Common.tell(sender, SimpleLocalization.Commands.ERROR);

			t.printStackTrace();
		}

		return true;
	}

	/**
	 * Executed when the command is run. You can get the variables sender and args directly,
	 * and use convenience checks in the simple command class.
	 */
	protected abstract void onCommand();

	/**
	 * Get a custom multilined usagem message to be shown instead of the one line one
	 *
	 * @return the multiline custom usage message, or null
	 */
	protected String[] getMultilineUsageMessage() {
		return null;
	}

	/**
	 * Shall we add your plugin's prefix when sending messages for players?
	 *
	 * @param args
	 * @return
	 */
	protected boolean addTellPrefix(String[] args) {
		return true;
	}

	// ----------------------------------------------------------------------
	// Convenience checks
	//
	// Here is how they work: When you command is executed, simply call any
	// of these checks. If they fail, an error will be thrown inside of
	// which will be a message for the player.
	//
	// We catch that error and send the message to the player without any
	// harm or console errors to your plugin. That is intended and saves time.
	// ----------------------------------------------------------------------

	/**
	 * Checks if the player is a console and throws an error if he is
	 *
	 * @throws CommandException
	 */
	protected final void checkConsole() throws CommandException {
		if (!isPlayer())
			throw new CommandException("&c" + SimpleLocalization.Commands.NO_CONSOLE);
	}

	/**
	 * Checks if the player has the given permission
	 *
	 * @param perm
	 * @throws CommandException
	 */
	public final void checkPerm(@NonNull String perm) throws CommandException {
		if (isPlayer() && !PlayerUtil.hasPerm(sender, perm))
			throw new CommandException(getPermissionMessage().replace("{permission}", perm));
	}

	/**
	 * Check if the command arguments are of the minimum length
	 *
	 * @param minimumLength
	 * @param falseMessage
	 * @throws CommandException
	 */
	protected final void checkArgs(int minimumLength, String falseMessage) throws CommandException {
		if (args.length < minimumLength)
			returnTell("&c" + falseMessage);
	}

	/**
	 * Checks if the given boolean is true
	 *
	 * @param value
	 * @param falseMessage
	 * @throws CommandException
	 */
	protected final void checkBoolean(boolean value, String falseMessage) throws CommandException {
		if (!value)
			returnTell("&c" + falseMessage);
	}

	/**
	 * Checks if the given object is not null
	 *
	 * @param value
	 * @param messageIfNull
	 * @throws CommandException
	 */
	protected final void checkNotNull(Object value, String messageIfNull) throws CommandException {
		if (value == null)
			returnTell("&c" + messageIfNull);
	}

	/**
	 * Attempts to find a non-vanished online player, failing with the message
	 * found at {@link SimpleLocalization.Player#NOT_ONLINE}
	 *
	 * @param name
	 * @return
	 * @throws CommandException
	 */
	protected final Player findPlayer(String name) throws CommandException {
		return findPlayer(name, SimpleLocalization.Player.NOT_ONLINE);
	}

	/**
	 * Attempts to find a non-vanished online player, failing with a false message
	 *
	 * @param name
	 * @param falseMessage
	 * @return
	 * @throws CommandException
	 */
	protected final Player findPlayer(String name, String falseMessage) throws CommandException {
		final Player player = PlayerUtil.getNickedNonVanishedPlayer(name);
		checkBoolean(player != null && player.isOnline(), falseMessage.replace("{player}", name));

		return player;
	}

	/**
	 * Finds an enumeration of a certain type, if it fails it prints a false message to the player
	 * You can use the {enum} variable in the false message for the name parameter
	 *
	 * @param <T>
	 * @param enumType
	 * @param name
	 * @param falseMessage
	 * @return
	 * @throws CommandException
	 */
	protected final <T extends Enum<T>> T findEnum(Class<T> enumType, String name, String falseMessage) throws CommandException {
		T found = null;

		try {
			found = ReflectionUtil.lookupEnum(enumType, name);
		} catch (final MissingEnumException ex) {
		}

		checkNotNull(found, falseMessage.replace("{enum}", name));
		return found;
	}

	/**
	 * A convenience method for parsing a number at the given args index
	 *
	 * @param index
	 * @param falseMessage
	 * @return
	 */
	protected final int findNumber(int index, String falseMessage) {
		checkBoolean(index < args.length, falseMessage);

		Integer parsed = null;

		try {
			parsed = Integer.parseInt(args[index]);

		} catch (final NumberFormatException ex) {
		}

		checkNotNull(parsed, falseMessage);
		return parsed;
	}

	// ----------------------------------------------------------------------
	// Other checks
	// ----------------------------------------------------------------------

	/**
	 * A convenience check for quickly determining if the sender has a given
	 * permission.
	 *
	 * TIP: For a more complete check use {@link #checkPerm(String)} that
	 * will automatically return your command if they lack the permission.
	 *
	 * @param permission
	 * @return
	 */
	protected final boolean hasPerm(String permission) {
		return PlayerUtil.hasPerm(sender, permission);
	}

	// ----------------------------------------------------------------------
	// Messaging
	// ----------------------------------------------------------------------

	/**
	 * Send the player a Replacer message
	 *
	 * @param replacer
	 */
	protected final void tell(Replacer replacer) {
		tell(replacer.getReplacedMessage());
	}

	/**
	 * Send a list of messages to the player
	 *
	 * @param messages
	 */
	protected final void tell(Collection<String> messages) {
		tell(messages.toArray(new String[messages.size()]));
	}

	/**
	 * Sends a simple message to the player
	 *
	 * @param messages
	 */
	protected final void tell(String messages) {
		Common.tell(sender, replacePlaceholders(messages));
	}

	/**
	 * Sends a multiline message to the player without plugins prefix
	 *
	 * @param messages
	 */
	protected final void tellNoPrefix(String... messages) {
		final boolean tellPrefix = Common.ADD_TELL_PREFIX;
		Common.ADD_TELL_PREFIX = false;

		tell(messages);

		Common.ADD_TELL_PREFIX = tellPrefix;
	}

	/**
	 * Sends a multiline message to the player, avoiding prefix if 3 lines or more
	 *
	 * @param messages
	 */
	protected final void tell(String... messages) {
		if (messages != null) {
			messages = replacePlaceholders(messages);

			if (messages.length > 2)
				Common.tellNoPrefix(sender, messages);
			else
				Common.tell(sender, messages);
		}
	}

	/**
	 * Sends a interactive chat component to the sender, not replacing any special
	 * variables just executing the {@link ChatComponent#send(CommandSender...)} method
	 * as a shortcut
	 *
	 * @param component
	 */
	protected final void tell(ChatComponent component) {
		component.send(sender);
	}

	/**
	 * Sends a message to the player and throws a message error, preventing further execution
	 *
	 * @param messages
	 * @throws CommandException
	 */
	protected final void returnTell(Collection<String> messages) throws CommandException {
		returnTell(messages.toArray(new String[messages.size()]));
	}

	/**
	 * Convenience method for returning the command with the {@link SimpleLocalization.Commands#INVALID_ARGUMENT}
	 * message for player
	 */
	protected final void returnInvalidArgs() {
		returnTell(SimpleLocalization.Commands.INVALID_ARGUMENT.replace("{label}", getLabel()));
	}

	/**
	 * Sends a message to the player and throws a message error, preventing further execution
	 *
	 * @param messages
	 * @throws CommandException
	 */
	protected final void returnTell(String... messages) throws CommandException {
		throw new CommandException(replacePlaceholders(messages));
	}

	// ----------------------------------------------------------------------
	// Placeholder
	// ----------------------------------------------------------------------

	/**
	 * Registers a new placeholder to be used when sending messages to the player
	 *
	 * @param placeholder
	 */
	protected final void addPlaceholder(Placeholder placeholder) {
		placeholders.add(placeholder);
	}

	/**
	 * Replaces placeholders in all messages
	 * To change them override {@link #replacePlaceholders(String)}
	 *
	 * @param messages
	 * @return
	 */
	protected final String[] replacePlaceholders(String[] messages) {
		for (int i = 0; i < messages.length; i++)
			messages[i] = replacePlaceholders(messages[i]).replace("{prefix}", Common.getTellPrefix());

		return messages;
	}

	/**
	 * Replaces placeholders in the message
	 *
	 * @param message
	 * @return
	 */
	protected String replacePlaceholders(String message) {
		// Replace basic labels
		message = replaceBasicPlaceholders0(message);

		// Replace {X} with arguments
		for (int i = 0; i <= args.length; i++)
			message = message.replace("{" + i + "}", Common.getOrEmpty(i == 0 ? getLabel() : args[i - 1]));

		// Replace saved placeholders
		for (final Placeholder placeholder : placeholders) {
			String toReplace = message;

			if (placeholder instanceof PositionPlaceholder) {
				final PositionPlaceholder arguedPlaceholder = (PositionPlaceholder) placeholder;

				if (args.length > arguedPlaceholder.getPosition())
					toReplace = args[arguedPlaceholder.getPosition()];
				else
					continue;
			}

			message = message.replace("{" + placeholder.getIdentifier() + "}", placeholder.replace(toReplace));
		}

		return message;
	}

	/**
	 * Internal method for replacing {label} {sublabel} and {plugin.name} placeholders
	 *
	 * @param message
	 * @return
	 */
	private final String replaceBasicPlaceholders0(String message) {
		return message
				.replace("{label}", getLabel())
				.replace("{sublabel}", this instanceof SimpleSubCommand ? ((SimpleSubCommand) this).getSublabels()[0] : super.getLabel())
				.replace("{plugin.name}", SimplePlugin.getNamed().toLowerCase());
	}

	/**
	 * Utility method to safely update the args, increasing them if the position is too high
	 *
	 * Used in placeholders
	 *
	 * @param position
	 * @param value
	 */
	protected final void setArg(int position, String value) {
		if (args.length <= position)
			args = Arrays.copyOf(args, position + 1);

		args[position] = value;
	}

	/**
	 * Convenience method for returning the last word in arguments
	 *
	 * @return
	 */
	protected final String getLastArg() {
		return args.length > 0 ? args[args.length - 1] : "";
	}

	// ----------------------------------------------------------------------
	// Tab completion
	// ----------------------------------------------------------------------

	/**
	 * Show tab completion suggestions when the given sender
	 * writes the command with the given arguments
	 *
	 * Tab completion is only shown if the sender has {@link #getPermission()}
	 *
	 * @param sender
	 * @param alias
	 * @param args
	 * @param location
	 *
	 * @return
	 *
	 * @deprecated location is not used
	 */
	@Deprecated
	@Override
	public final List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) throws IllegalArgumentException {
		return tabComplete(sender, alias, args);
	}

	/**
	 * Show tab completion suggestions when the given sender
	 * writes the command with the given arguments
	 *
	 * Tab completion is only shown if the sender has {@link #getPermission()}
	 *
	 * @param sender
	 * @param alias
	 * @param args
	 *
	 * @return
	 */
	@Override
	public final List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
		this.sender = sender;
		this.label = alias;
		this.args = args;

		if (hasPerm(getPermission())) {
			List<String> suggestions = tabComplete();

			// Return online player names when suggestions are null - simulate Bukkit behaviour
			if (suggestions == null)
				suggestions = completeLastWordPlayerNames();

			return suggestions;
		}

		return new ArrayList<>();
	}

	/**
	 * Override this method to support tab completing in your command.
	 *
	 * You can then use "sender", "label" or "args" fields from {@link SimpleCommand}
	 * class normally and return a list of tab completion suggestions.
	 *
	 * We already check for {@link #getPermission()} and only call this method if the
	 * sender has it.
	 *
	 * TIP: Use {@link #completeLastWord(Iterable)} and {@link #getLastArg()} methods
	 * 		in {@link SimpleCommand} for your convenience
	 *
	 *
	 * @return the list of suggestions to complete, or null to complete player names automatically
	 */
	protected List<String> tabComplete() {
		return null;
	}

	/**
	 * Convenience method for automatically completing the last word
	 * with the given suggestions. We sort them and only select ones
	 * that the last word starts with.
	 *
	 * @param <T>
	 * @param suggestions
	 * @return
	 */
	protected final <T> List<String> completeLastWord(List<String> suggestions) {
		return TabUtil.complete(getLastArg(), suggestions);
	}

	/**
	 * Convenience method for automatically completing the last word
	 * with the given suggestions. We sort them and only select ones
	 * that the last word starts with.
	 *
	 * @param <T>
	 * @param suggestions
	 * @return
	 */
	protected final <T> List<String> completeLastWord(Iterable<String> suggestions) {
		return TabUtil.complete(getLastArg(), suggestions);
	}

	/**
	 * Convenience method for automatically completing the last word
	 * with the given suggestions. We sort them and only select ones
	 * that the last word starts with.
	 *
	 * @param <T>
	 * @param suggestions
	 * @return
	 */
	protected final <T> List<String> completeLastWord(T... suggestions) {
		return TabUtil.complete(getLastArg(), suggestions);
	}

	/**
	 * Convenience method for completing all player names that the sender can see
	 * and that are not vanished
	 *
	 * TIP: You can simply return null for the same behaviour
	 *
	 * @return
	 */
	protected final List<String> completeLastWordPlayerNames() {
		return TabUtil.complete(getLastArg(), isPlayer() ? Common.getPlayerNames(getPlayer()) : Common.getPlayerNames());
	}

	// ----------------------------------------------------------------------
	// Temporary variables and safety
	// ----------------------------------------------------------------------

	/**
	 * Attempts to get the sender as player, only works if the sender is actually a player,
	 * otherwise we return null
	 *
	 * @return
	 */
	protected final Player getPlayer() {
		return isPlayer() ? (Player) getSender() : null;
	}

	/**
	 * Return whether the sender is a living player
	 *
	 * @return
	 */
	protected final boolean isPlayer() {
		return sender instanceof Player;
	}

	/**
	 * Sets the minimum number of arguments to run this command
	 *
	 * @param minArguments
	 */
	protected final void setMinArguments(int minArguments) {
		Valid.checkBoolean(minArguments >= 0, "Minimum arguments must be 0 or greater");

		this.minArguments = minArguments;
	}

	/**
	 * Get the permission for this command, either the one you set or our from Localization
	 */
	@Override
	public final String getPermissionMessage() {
		return Common.getOrDefault(super.getPermissionMessage(), "&c" + SimpleLocalization.NO_PERMISSION);
	}

	/**
	 * By default we check if the player has the permission you set in setPermission.
	 *
	 * If that is null, we check for the following:
	 * {yourpluginname}.command.{label} for {@link SimpleCommand}
	 * {yourpluginname}.command.{label}.{sublabel} for {@link SimpleSubCommand}
	 *
	 * We handle lacking permissions automatically and return with an no-permission message
	 * when the player lacks it.
	 *
	 * @return
	 */
	@Override
	public final String getPermission() {
		return super.getPermission() == null ? null : replaceBasicPlaceholders0(super.getPermission());
	}

	/**
	 * Get the permission without replacing {plugin.name}, {label} or {sublabel}
	 *
	 * @deprecated internal use only
	 * @return
	 */
	@Deprecated
	public final String getRawPermission() {
		return super.getPermission();
	}

	/**
	 * Sets the permission required for this command to run. If you set the
	 * permission to null we will not require any permission (unsafe).
	 *
	 * @param
	 */
	@Override
	public final void setPermission(String permission) {
		super.setPermission(permission);
	}

	/**
	 * Get the sender of this command
	 *
	 * @return
	 */
	protected final CommandSender getSender() {
		Valid.checkNotNull(sender, "Sender cannot be null");

		return sender;
	}

	/**
	 * Get aliases for this command
	 */
	@Override
	public final List<String> getAliases() {
		return super.getAliases();
	}

	/**
	 * Get description for this command
	 */
	@Override
	public final String getDescription() {
		return super.getDescription();
	}

	/**
	 * Get the name of this command
	 */
	@Override
	public final String getName() {
		return super.getName();
	}

	/**
	 * Get the usage message of this command
	 */
	@Override
	public final String getUsage() {
		return super.getUsage();
	}

	/**
	 * Get the most recent label for this command
	 */
	@Override
	public final String getLabel() {
		return label;
	}

	/**
	 * Get the label given when the command was created or last updated with {@link #setLabel(String)}
	 *
	 * @return
	 */
	public final String getMainLabel() {
		return super.getLabel();
	}

	/**
	 * Updates the label of this command
	 */
	@Override
	public final boolean setLabel(String name) {
		this.label = name;

		return super.setLabel(name);
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof SimpleCommand ? ((SimpleCommand) obj).getLabel().equals(this.getLabel()) && ((SimpleCommand) obj).getAliases().equals(this.getAliases()) : false;
	}

	@Override
	public String toString() {
		return "Command{label=/" + label + "}";
	}
}
