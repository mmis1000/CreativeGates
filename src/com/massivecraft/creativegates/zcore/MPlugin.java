package com.massivecraft.creativegates.zcore;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.massivecraft.creativegates.zcore.persist.EM;
import com.massivecraft.creativegates.zcore.persist.SaveTask;
import com.massivecraft.creativegates.zcore.util.LibLoader;
import com.massivecraft.creativegates.zcore.util.PermUtil;
import com.massivecraft.creativegates.zcore.util.Persist;
import com.massivecraft.creativegates.zcore.util.TextUtil;


public abstract class MPlugin extends JavaPlugin
{
	// Some utils
	public Persist persist;
	public TextUtil txt;
	public LibLoader lib;
	public PermUtil perm;
	
	// Persist related
	public Gson gson;	
	private Integer saveTask = null;
	private boolean autoSave = true;
	public boolean getAutoSave() {return this.autoSave;}
	public void setAutoSave(boolean val) {this.autoSave = val;}
	
	// Listeners
	private MPluginSecretPlayerListener mPluginSecretPlayerListener; 
	private MPluginSecretServerListener mPluginSecretServerListener;
	
	// Our stored base commands
	private List<MCommand<?>> baseCommands = new ArrayList<MCommand<?>>();
	public List<MCommand<?>> getBaseCommands() { return this.baseCommands; }

	// -------------------------------------------- //
	// ENABLE
	// -------------------------------------------- //
	private long timeEnableStart;
	public boolean preEnable()
	{
		log("=== ENABLE START ===");
		timeEnableStart = System.currentTimeMillis();
		
		// Ensure basefolder exists!
		this.getDataFolder().mkdirs();
		
		// Create Utility Instances
		this.perm = new PermUtil(this);
		this.persist = new Persist(this);
		this.lib = new LibLoader(this);
		
		if ( ! lib.require("gson.jar", "http://search.maven.org/remotecontent?filepath=com/google/code/gson/gson/1.7.1/gson-1.7.1.jar")) return false;
		this.gson = this.getGsonBuilder().create();
		
		this.txt = new TextUtil();
		initTXT();
		
		// Create and register listeners
		this.mPluginSecretPlayerListener = new MPluginSecretPlayerListener(this);
		this.mPluginSecretServerListener = new MPluginSecretServerListener(this);
		PluginManager pm = this.getServer().getPluginManager();
		pm.registerEvent(Event.Type.PLAYER_PRELOGIN, this.mPluginSecretPlayerListener, Event.Priority.Lowest, this);
		pm.registerEvent(Event.Type.PLAYER_CHAT, this.mPluginSecretPlayerListener, Event.Priority.Low, this);
		pm.registerEvent(Event.Type.PLAYER_COMMAND_PREPROCESS, this.mPluginSecretPlayerListener, Event.Priority.Lowest, this);
		pm.registerEvent(Event.Type.SERVER_COMMAND, this.mPluginSecretServerListener, Event.Priority.Lowest, this);
		
		
		// Register recurring tasks
		long saveTicks = 20 * 60 * 30; // Approximately every 30 min
		if (saveTask == null)
		{
			saveTask = Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new SaveTask(this), saveTicks, saveTicks);
		}
		
		return true;
	}
	
	public void postEnable()
	{
		log("=== ENABLE DONE (Took "+(System.currentTimeMillis()-timeEnableStart)+"ms) ===");
	}
	
	public void onDisable()
	{
		if (saveTask != null)
		{
			this.getServer().getScheduler().cancelTask(saveTask);
			saveTask = null;
		}
		EM.saveAllToDisc();
		log("Disabled");
	}
	
	public void suicide()
	{
		log("Now I suicide!");
		this.getServer().getPluginManager().disablePlugin(this);
	}

	// -------------------------------------------- //
	// Register Event convenience method
	// -------------------------------------------- //
	
	public void registerEvent(Event.Type type, Listener listener, Event.Priority priority)
	{
		Bukkit.getServer().getPluginManager().registerEvent(type, listener, priority, this);	
	}
	
	// -------------------------------------------- //
	// Some inits...
	// You are supposed to override these in the plugin if you aren't satisfied with the defaults
	// The goal is that you always will be satisfied though.
	// -------------------------------------------- //

	public GsonBuilder getGsonBuilder()
	{
		return new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.serializeNulls()
		.excludeFieldsWithModifiers(Modifier.TRANSIENT, Modifier.VOLATILE);
	}
	
	// -------------------------------------------- //
	// LANG AND TAGS
	// -------------------------------------------- //
	
	// These are not supposed to be used directly.
	// They are loaded and used through the TextUtil instance for the plugin.
	public Map<String, String> rawTags = new LinkedHashMap<String, String>();
	
	public void addRawTags()
	{
		this.rawTags.put("l", "<green>"); // logo
		this.rawTags.put("a", "<gold>"); // art
		this.rawTags.put("n", "<silver>"); // notice
		this.rawTags.put("i", "<yellow>"); // info
		this.rawTags.put("g", "<lime>"); // good
		this.rawTags.put("b", "<rose>"); // bad
		this.rawTags.put("h", "<pink>"); // highligh
		this.rawTags.put("c", "<aqua>"); // command
		this.rawTags.put("p", "<teal>"); // parameter
	}
	
	public void initTXT()
	{
		this.addRawTags();
		
		Type type = new TypeToken<Map<String, String>>(){}.getType();
		
		Map<String, String> tagsFromFile = this.persist.load(type, "tags");
		if (tagsFromFile != null) this.rawTags.putAll(tagsFromFile);
		this.persist.save(this.rawTags, "tags");
		
		for (Entry<String, String> rawTag : this.rawTags.entrySet())
		{
			this.txt.tags.put(rawTag.getKey(), TextUtil.parseColor(rawTag.getValue()));
		}
	}
	
	// -------------------------------------------- //
	// COMMAND HANDLING
	// -------------------------------------------- //

	public boolean handleCommand(CommandSender sender, String commandString, boolean testOnly)
	{
		boolean noSlash = false;
		if (commandString.startsWith("/"))
		{
			noSlash = true;
			commandString = commandString.substring(1);
		}
		
		for (MCommand<?> command : this.getBaseCommands())
		{
			if (noSlash && ! command.allowNoSlashAccess) continue;
			
			for (String alias : command.aliases)
			{
				if (commandString.startsWith(alias+" ") || commandString.equals(alias))
				{
					List<String> args = new ArrayList<String>(Arrays.asList(commandString.split("\\s+")));
					args.remove(0);
					if (testOnly) return true;
					command.execute(sender, args);
					return true;
				}
			}
		}
		return false;
	}
	
	public boolean handleCommand(CommandSender sender, String commandString)
	{
		return this.handleCommand(sender, commandString, false);
	}
	
	// -------------------------------------------- //
	// HOOKS
	// -------------------------------------------- //
	public void preAutoSave()
	{
		
	}
	
	public void postAutoSave()
	{
		
	}
	
	// -------------------------------------------- //
	// LOGGING
	// -------------------------------------------- //
	public void log(Object msg)
	{
		log(Level.INFO, msg);
	}
	
	public void log(Level level, Object msg)
	{
		Logger.getLogger("Minecraft").log(level, "["+this.getDescription().getFullName()+"] "+msg);
	}
}
