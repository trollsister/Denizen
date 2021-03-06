package net.aufdemrand.denizen.scriptEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

import net.aufdemrand.denizen.Denizen;
import net.aufdemrand.denizen.DenizenCharacter;
import net.aufdemrand.denizen.bookmarks.Bookmarks.BookmarkType;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;


/**
 * Contains methods used to parse and execute scripts, 
 * initiated by some kind of event trigger or interaction.
 * 
 * @author Jeremy
 * 
 */

public class ScriptEngine {


	/* Denizen Constructor */

	private Denizen plugin;
	public ScriptEngine(Denizen denizen) {
		plugin = denizen;
	}	


	/* ENUMS to help with dealing with multiple types of Triggers/Queues */

	public enum TriggerType {
		ATTACK, CLICK, CHAT, PROXIMITY, TASK, LOCATION
	}

	public enum QueueType {
		TRIGGER, TASK, ACTIVITY, CUSTOM
	}

	public enum CommandHas {
		SOME, MORE, MOST;
	}

	private Map<Player, List<ScriptCommand>> triggerQue = new ConcurrentHashMap<Player, List<ScriptCommand>>();
	private Map<Player, List<ScriptCommand>>    taskQue = new ConcurrentHashMap<Player, List<ScriptCommand>>();
	private Map<NPC, List<ScriptCommand>>   activityQue = new ConcurrentHashMap<NPC, List<ScriptCommand>>();



	/* Processes commands from the Queues. */

	public void runQueues() {

		/* First the triggerQue, primary script queue for Players */

		if (!triggerQue.isEmpty()) {	

			/* Attempt to run a command for each player. The attempted command (and attached info) info is 
			 * in theEntry */
			for (Entry<Player, List<ScriptCommand>> theEntry : triggerQue.entrySet()) {
				if (!theEntry.getValue().isEmpty()) {

					/* Check the time of the command to see if it has been delayed with a WAIT command. Only 
					 * proceed for the player if the time on the command is less than the current time. 
					 * If it's more, then this entry will be skipped and saved for next time. */
					if (theEntry.getValue().get(0).getDelayedTime() < System.currentTimeMillis()) {

						/* Feeds the executer ScriptCommands as long as they are instant commands ("^"), otherwise
						 * runs one command, removes it from the queue, and moves on to the next player. */
						boolean instantly;

						do { 
							instantly = false;
							ScriptCommand theCommand = theEntry.getValue().get(0);
							theCommand.setSendingQueue(QueueType.TRIGGER);
							plugin.executer.execute(theCommand);

							// Instant command check
							if (theEntry.getValue().size() > 1
									&& theEntry.getValue().get(0).isInstant())
								instantly = true; 
							// ----

							theEntry.getValue().remove(0);

							/* Updates the triggerQue map */
							triggerQue.put(theEntry.getKey(), theEntry.getValue());
						} while (instantly);
					}
				}
			}
			/* Next Player */
		}


		/* Now the taskQue, the alternate script queue for Players */

		if (!taskQue.isEmpty()) {	
			for (Entry<Player, List<ScriptCommand>> theEntry : taskQue.entrySet()) {
				if (!theEntry.getValue().isEmpty()) {
					if (theEntry.getValue().get(0).getDelayedTime() < System.currentTimeMillis()) {
						boolean instantly;
						do { 
							instantly = false;
							ScriptCommand theCommand = theEntry.getValue().get(0);
							theCommand.setSendingQueue(QueueType.TASK);
							plugin.executer.execute(theCommand);

							// Instant command check
							if (theEntry.getValue().size() > 1
									&& theEntry.getValue().get(0).isInstant())
								instantly = true; 
							// ----

							theEntry.getValue().remove(0);
							taskQue.put(theEntry.getKey(), theEntry.getValue());

						} while (instantly);

					}
				}
			}
			/* Next Player */
		}


		/* 
		 * TODO: activityQue
		 */

	}



	/* Schedules activity scripts to Denizens based on their schedule defined in the config.
	 * Runs every Minecraft hour. 
	 * 
	 * This will be the backbone to automated activity scripts. Currently this is not used
	 * any further than what's in this method, but will be build upon soon.	 */

	public void scheduleScripts() {

		if (plugin.utilities.getDenizens().isEmpty()) return;
		for (NPC thisDenizen : plugin.utilities.getDenizens()) {
			if (thisDenizen.isSpawned())	{
				int denizenTime = Math.round(thisDenizen.getBukkitEntity().getWorld().getTime() / 1000);
				List<String> denizenActivities = plugin.getAssignments().getStringList("Denizens." + thisDenizen.getName() + ".Scheduled Activities");
				if (!denizenActivities.isEmpty()) {
					for (String activity : denizenActivities) {
						if (activity.startsWith(String.valueOf(denizenTime))) {
							// plugin.getServer().broadcastMessage("Updating Activity Script for " + aDenizen.getName());
							plugin.getSaves().set("Denizens." + thisDenizen.getName() + ".Active Activity Script", activity.split(" ", 2)[1]);
							plugin.saveSaves();
						}
					}
				}
			}
		}
	}



	/* Parses the scripts for Chat Triggers and sends new ScriptCommands to the queue if
	 * found matched. Returning FALSE will cancel intervention and allow the PlayerChatEvent
	 * to pass through.	 */

	public boolean parseChatScript(NPC theDenizen, Player thePlayer, String theScript, String playerMessage) {

		int theStep = getCurrentStep(thePlayer, theScript);
		List<ScriptCommand> scriptCommands = new ArrayList<ScriptCommand>();

		/* Get Chat Triggers and check each to see if there are any matches. */
		List<String> ChatTriggerList = plugin.getScript.getChatTriggers(theScript, theStep);
		for (int x = 0; x < ChatTriggerList.size(); x++ ) {

			/* The texts required to trigger. */
			String chatTriggers = ChatTriggerList.get(x)
					.replace("<PLAYER>", thePlayer.getName())
					.replace("<DISPLAYNAME>", ChatColor.stripColor(thePlayer.getDisplayName())).toLowerCase();

			/* The in-game friendly Chat Trigger text to display if triggered. */
			String chatText = plugin.getScripts()
					.getString(theScript + ".Steps." + theStep + ".Chat Trigger." + String.valueOf(x + 1) + ".Trigger")
					.replace("/", "");

			boolean letsProceed = false;
			for (String chatTrigger : chatTriggers.split(":")) {
				if (playerMessage.toLowerCase().contains(chatTrigger)) letsProceed = true;
			}

			if (letsProceed) {

				/* Trigger matches, let's talk to the Denizen and send the script to the 
				 * triggerQue. No need to continue the loop. */
				plugin.getPlayer.talkToDenizen(theDenizen, thePlayer, chatText);

				List<String> chatScriptItems = plugin.getScripts().getStringList(theScript + ".Steps." + theStep + ".Chat Trigger." + String.valueOf(x + 1) + ".Script");
				for (String thisItem : chatScriptItems) {
					String[] scriptEntry = new String[2];
					scriptEntry = thisItem.split(" ", 2);
					try {
						/* Build new script commands */
						scriptCommands.add(new ScriptCommand(scriptEntry[0], buildArgs(scriptEntry[1]), thePlayer, theDenizen, theScript, theStep, playerMessage, chatText));
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				/* New ScriptCommand list built, now let's add it into the queue */
				List<ScriptCommand> scriptCommandList = triggerQue.get(thePlayer);

				/* Keeps the commandQue from removing items while
				working on them here. They will be added back in. */ 
				triggerQue.remove(thePlayer); 

				scriptCommandList.addAll(scriptCommands);
				triggerQue.put(thePlayer, scriptCommandList);

				return true;
			}
		}

		/* If we have made it to this point, there were no matching triggers. */
		if (plugin.settings.ChatGloballyIfFailedChatTriggers()) return false;

		else {
			plugin.getPlayer.talkToDenizen(theDenizen, thePlayer, playerMessage);
			String noscriptChat = null;

			/* Checks the denizen for a custom message, else uses the default */
			if (plugin.getAssignments().contains("Denizens." + theDenizen.getName() + ".Texts.No Chat Triggers Met")) 
				noscriptChat = plugin.getAssignments().getString("Denizens." + theDenizen.getName()	+ ".Texts.No Chat Triggers Met");
			else noscriptChat = plugin.settings.DefaultNoChatTriggersMetText();

			plugin.getDenizen.talkToPlayer(theDenizen, thePlayer, plugin.getDenizen.formatChatText(noscriptChat, "CHAT", thePlayer, theDenizen)[0], null, "CHAT");
			return true;
		}
	}



	/* Parses the script for a click trigger */

	public boolean parseClickScript(NPC theDenizen, Player thePlayer, String theScript) {

		int theStep = getCurrentStep(thePlayer, theScript);
		List<ScriptCommand> scriptCommands = new ArrayList<ScriptCommand>();

		/* Let's get the Script from the file and turn it into ScriptCommands */
		List<String> chatScriptItems = plugin.getScripts().getStringList(theScript + ".Steps." + theStep + ".Click Trigger.Script");
		if (plugin.debugMode) plugin.getLogger().log(Level.INFO, "Parsing: " + theScript + ".Steps." + theStep + ".Click Trigger.Script");
		if (plugin.debugMode) plugin.getLogger().log(Level.INFO, "Number of items to parse: " + chatScriptItems.size());

		for (String thisItem : chatScriptItems) {
			String[] scriptEntry = new String[2];
			if (thisItem.split(" ", 2).length == 1) {
				scriptEntry[0] = thisItem;
				scriptEntry[1] = null;
			} else {
				scriptEntry = thisItem.split(" ", 2);
			}

			try {
				/* Build new script commands */
				scriptCommands.add(new ScriptCommand(scriptEntry[0], buildArgs(scriptEntry[1]), thePlayer, theDenizen, theScript, theStep));
				if (plugin.debugMode) plugin.getLogger().log(Level.INFO, "Building ScriptCommand with " + thisItem);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		/* New ScriptCommand list built, now let's add it into the queue */
		List<ScriptCommand> scriptCommandList = new ArrayList<ScriptCommand>();
		if (triggerQue.containsKey(thePlayer))
			scriptCommandList.addAll(triggerQue.get(thePlayer));

		/* Keeps the commandQue from removing items while
		working on them here. They will be added back in. */ 
		triggerQue.remove(thePlayer); 

		if (!scriptCommands.isEmpty())
			scriptCommandList.addAll(scriptCommands);
		else
			if (plugin.debugMode) plugin.getLogger().log(Level.SEVERE, "No items in the script to add!");

		triggerQue.put(thePlayer, scriptCommandList);

		return true;
	}



	/* Parses the script for a task trigger */

	public boolean parseTaskScript(Player thePlayer, String theScript) {

		List<ScriptCommand> scriptCommands = new ArrayList<ScriptCommand>();

		/* Let's get the Script from the file and turn it into ScriptCommands */
		List<String> chatScriptItems = plugin.getScripts().getStringList(theScript + ".Script");
		for (String thisItem : chatScriptItems) {
			String[] scriptEntry = new String[2];
			scriptEntry = thisItem.split(" ", 2);
			try {
				/* Build new script commands */
				scriptCommands.add(new ScriptCommand(scriptEntry[0], buildArgs(scriptEntry[1]), thePlayer, theScript));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		/* New ScriptCommand list built, now let's add it into the queue */
		List<ScriptCommand> scriptCommandList = taskQue.get(thePlayer);

		/* Keeps the commandQue from removing items while
		working on them here. They will be added back in. */ 
		taskQue.remove(thePlayer); 

		scriptCommandList.addAll(scriptCommands);
		taskQue.put(thePlayer, scriptCommandList);

		return true;
	}



	/** Injects commands into a QueueType  */

	public void injectToQue(Player thePlayer, List<ScriptCommand> scriptCommands, QueueType queueType, int thePosition) {

		List<ScriptCommand> scriptCommandList;

		switch (queueType) {

		case TRIGGER:
			scriptCommandList = taskQue.get(thePlayer);
			taskQue.remove(thePlayer); 
			if (thePosition > scriptCommandList.size() || thePosition < 0) thePosition = 1;
			if (scriptCommandList.size() == 0) thePosition = 0;
			scriptCommandList.addAll(thePosition, scriptCommands);
			taskQue.put(thePlayer, scriptCommandList);
			break;

		case TASK:
			scriptCommandList = taskQue.get(thePlayer);
			taskQue.remove(thePlayer); 
			if (thePosition > scriptCommandList.size() || thePosition < 0) thePosition = 1;
			if (scriptCommandList.size() == 0) thePosition = 0;
			scriptCommandList.addAll(thePosition, scriptCommands);
			taskQue.put(thePlayer, scriptCommandList);
			break;
		}

		return;
	}

	public void injectToQue(Denizen theDenizen, List<ScriptCommand> scriptCommands, QueueType queueType, int thePosition) {

		/* 
		 * TODO: ActivityQue injection sequence
		 */

	}



	/** Adds commands to a QueueType  */

	public void addToQue(Player thePlayer, List<ScriptCommand> scriptCommands, QueueType queueType) {

		List<ScriptCommand> scriptCommandList;

		switch (queueType) {

		case TRIGGER:
			scriptCommandList = taskQue.get(thePlayer);
			taskQue.remove(thePlayer); 
			scriptCommandList.addAll(scriptCommands);
			taskQue.put(thePlayer, scriptCommandList);
			break;

		case TASK:
			scriptCommandList = taskQue.get(thePlayer);
			taskQue.remove(thePlayer); 
			scriptCommandList.addAll(scriptCommands);
			taskQue.put(thePlayer, scriptCommandList);
			break;
		}

		return;
	}

	public void addToQue(Denizen theDenizen, List<ScriptCommand> scriptCommands, QueueType queueType) {

		/* 
		 * TODO: ActivityQue injection sequence
		 */

	}



	public void newLocationTask(Player thePlayer, NPC theDenizen, String theLocation, int theDuration, int theLeeway, String theScript) {

		long taskId = System.currentTimeMillis();

		/* Add new task to list */
		List<String> listAll = plugin.getSaves().getStringList("Players." + thePlayer.getName() + ".Tasks.List All.Locations");
		listAll.add(theLocation + ";" + theDenizen.getName() + ";" + taskId);
		plugin.getSaves().set("Players." + thePlayer.getName() + ".Tasks.List All.Locations", listAll);

		/* Populate task entry */
		String taskString = "Players." + thePlayer.getName() + ".Tasks.List Entries." + taskId + ".";

		plugin.getSaves().set(taskString + "Type", "Location");
		plugin.getSaves().set(taskString + "Leeway", theLeeway);
		plugin.getSaves().set(taskString + "Duration", theDuration);
		plugin.getSaves().set(taskString + "Script", theScript);

		plugin.saveSaves();

	}



	public void finishLocationTask(Player thePlayer, String taskId) {

		List<String> listAll = plugin.getSaves().getStringList("Players." + thePlayer.getName() + ".Tasks.List All.Locations");			
		List<String> newList = new ArrayList<String>();

		for (String theTask : listAll) {
			if (!theTask.contains(taskId)) newList.add(theTask); 
		}

		if (newList.isEmpty()) plugin.getSaves().set("Players." + thePlayer.getName() + ".Tasks.List All.Locations", null);
		else plugin.getSaves().set("Players." + thePlayer.getName() + ".Tasks.List All.Locations", newList);

		String theScript = plugin.getSaves().getString("Players." + thePlayer.getName() + ".Tasks.List Entries." + taskId + ".Script");
		plugin.getSaves().set("Players." + thePlayer.getName() + ".Tasks.List Entries." + taskId, null);
		plugin.saveSaves();

		parseTaskScript(thePlayer, theScript);

	}



	public void enforcePosition() {
		if (plugin.utilities.getDenizens() != null) {
			for (NPC theDenizen : plugin.utilities.getDenizens()) {
				if (plugin.getSaves().contains("Denizens." + theDenizen.getName() + ".Position.Standing")) {
					if (!plugin.getSaves().getString("Denizens." + theDenizen.getName() + ".Position.Standing").isEmpty()) {

						Location enforcedLoc = plugin.bookmarks.get(theDenizen.getName(), 
								plugin.getSaves().getString("Denizens." + theDenizen.getName() + ".Position.Standing"), 
								BookmarkType.LOCATION);

						if (!plugin.bookmarks.checkLocation(theDenizen, enforcedLoc, 0) && !theDenizen.getNavigator().isNavigating())
							theDenizen.getNavigator().setTarget(enforcedLoc);

					}
				}
			}
		}
	}



	/* Builds arguments array, recognizing items in quotes as a single item 
	 * 
	 * Thanks to Jan Goyvaerts from 
	 * http://stackoverflow.com/questions/366202/regex-for-splitting-a-string-using-space-when-not-surrounded-by-single-or-double
	 * as this is pretty much a copy/paste.
	 * 
	 * Perfect!  */

	private String[] buildArgs(String stringArgs) {

		if (stringArgs == null) return null;

		List<String> matchList = new ArrayList<String>();
		Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
		Matcher regexMatcher = regex.matcher(stringArgs);
		while (regexMatcher.find()) {
			if (regexMatcher.group(1) != null) {
				// Add double-quoted string without the quotes
				matchList.add(regexMatcher.group(1));
			} else if (regexMatcher.group(2) != null) {
				// Add single-quoted string without the quotes
				matchList.add(regexMatcher.group(2));
			} else {
				// Add unquoted word
				matchList.add(regexMatcher.group());
			}
		} 
		String[] split = new String[matchList.size()];
		matchList.toArray(split);
		return split;
	}



	/* Engaged NPCs cannot interact with Players */

	private Map<NPC, Long> engagedNPC = new HashMap<NPC, Long>();

	public boolean getEngaged(NPC theDenizen) {
		if (engagedNPC.containsKey(theDenizen)) 
			if (engagedNPC.get(theDenizen) > System.currentTimeMillis())
				return true;
		return false;
	}

	public void setEngaged(NPC theDenizen, boolean engaged) {
		if (engaged) engagedNPC.put(theDenizen, System.currentTimeMillis() + plugin.settings.EngageTimeoutInSeconds() * 1000 );
		if (!engaged) engagedNPC.remove(theDenizen);
	}

	public void setEngaged(NPC theDenizen, Integer duration) {
		engagedNPC.put(theDenizen, System.currentTimeMillis() + duration * 1000 );

	}


	/* 
	 * GetCurrentStep
	 *
	 * Requires the Player and the Script.
	 * Reads the config.yml to find the current step that the player is on
	 * for the specified script.
	 *
	 */

	public int getCurrentStep(Player thePlayer, String theScript) {

		int currentStep = 1;
		if (plugin.getSaves().getString("Players." + thePlayer.getName() + "." + theScript + "." + "Current Step") != null)
			currentStep =  plugin.getSaves().getInt("Players." + thePlayer.getName() + "." + theScript	+ "." + "Current Step");

		return currentStep;
	}

}