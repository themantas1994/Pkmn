# Expansion E3, leg 8: Prism Tablets (Clan Archive, Route 12, Dragon's Roost, Scalemirror Pond) and the Hermit's
# trial (Dragon's Roost, Draco Meteor).

# ------------------------------------------------------------------ Prism Tablets
script p4c_archivist
  faceplayer
  if quest p4c_prism_tablets done
    say "WEN: I've re-shelved the whole Prism section. Twice. It's the most exciting week of my career."
  elif flag p4c_tablet_frost and flag p4c_tablet_sky and flag p4c_tablet_pond
    if not quest p4c_prism_tablets started
      quest p4c_prism_tablets start
    end
    say "WEN: All three rubbings! Let me lay them side by side... The verses fit together!"
    say "WEN: \"A black star fell and drank the sun. The founders gave it crystal to sleep in. When crystal leaves the mountain, the star wakes hungry.\""
    say "WEN: Forty years ago a ship carried Prism crystals off the mountain, and the sun went dark at noon. Now Team Eclipse is hauling crystals the same way...\pThey're not stealing light. They're waking the star. Take these, and please, warn Ryuga."
    tm meteorbeam
    giveitem cometshard
    quest p4c_prism_tablets done
  elif quest p4c_prism_tablets started
    say "WEN: Three tablets: one on Route 12's ridge, one on the Dragon's Roost, and one, legend says, standing in Scalemirror Pond itself. Bring me rubbings of all three!"
  else
    say "WEN: Welcome to the Clan Archive. I'm Wen, archivist. Please don't touch the scrolls. Or breathe on them."
    say "WEN: Our oldest records mention three Prism Tablets: one on the ridge over Route 12, one on the Dragon's Roost and one in Scalemirror Pond.\pTogether they're said to tell what really sleeps on Mt. Prism. Would you make rubbings for me?"
    quest p4c_prism_tablets start
  end
end

script p4c_archive_shelf
  say "\"The Dragon Clan: A History.\" Volume 1 of 40. Chapter 1 is just a list of Dragonite names."
end

script p4c_tablet_frost
  say "An ancient stone tablet, glazed with frost. The carving shows a black star falling toward a mountain."
  say "\"A black star fell and drank the sun...\""
  if not flag p4c_tablet_frost
    setflag p4c_tablet_frost
    say "You made a rubbing of the Frost Tablet."
    if quest p4c_prism_tablets started
      quest p4c_prism_tablets next
    end
  end
end

script p4c_tablet_sky
  say "A weathered tablet between the dragon statues. Robed figures pour crystals over a sleeping star."
  say "\"...The founders gave it crystal to sleep in...\""
  if not flag p4c_tablet_sky
    setflag p4c_tablet_sky
    say "You made a rubbing of the Sky Tablet."
    if quest p4c_prism_tablets started
      quest p4c_prism_tablets next
    end
  end
end

script p4c_tablet_pond
  say "A tablet rises out of the pond, polished smooth by the water. A star opens one great eye."
  say "\"...When crystal leaves the mountain, the star wakes hungry.\""
  if not flag p4c_tablet_pond
    setflag p4c_tablet_pond
    say "You made a rubbing of the Pond Tablet."
    if quest p4c_prism_tablets started
      quest p4c_prism_tablets next
    end
  end
end

# ------------------------------------------------------------------ The Hermit's trial (Roost Shrine)
script p4c_roost_sorin
  faceplayer
  if quest p4c_roost_trial done
    say "SORIN: Draco Meteor, given to a trainer who earned it. Draca will be insufferable about this. Good."
  elif quest p3b_dragon_trial done
    say "SORIN: You've passed Draca's little quiz. Knowing about dragons is one thing.\pStanding before one is another. Show me."
    if not quest p4c_roost_trial started
      quest p4c_roost_trial start
    end
    battle p4c_dr_hermit_sorin
    if result win
      say "SORIN: Ha! The Clan's oldest technique is yours. Every dragon can learn it; few deserve it."
      tm dracometeor
      quest p4c_roost_trial done
      say "SORIN: When the black star stirs, the dragons of the Roost roar. They roared forty years ago. They're roaring again. Hurry."
    end
  else
    say "SORIN: Another pilgrim. Firewood goes by the door.\pOh, you want the Clan's secret technique? Pass Elder Draca's trial at the Dragon Shrine first. Then we'll talk."
    if not quest p4c_roost_trial started
      quest p4c_roost_trial start
    end
  end
end

script p4c_roost_apprentice
  faceplayer
  if quest p4c_roost_trial done
    say "APPRENTICE: You beat the master?! He let me watch! I mean, I was here anyway. I live here."
  else
    say "APPRENTICE: The master only teaches Draco Meteor to people who beat him. Nobody's beaten him since Elder Draca. She was nineteen!"
  end
end

# ------------------------------------------------------------------ The second lens (Umbral Pass, Eclipse Workshop)
script p4c_up_haru
  faceplayer
  if quest p4c_second_lens done
    say "HARU: The spare lens is rubble and the Clan owes you twice over. Now get up that mountain!"
  elif flag p4c_lens_destroyed
    say "HARU: You smashed the spare lens? Ha! Umbra gets one shot at the summit and no second chances."
    if not quest p4c_second_lens started
      quest p4c_second_lens start
    end
    tm darkpulse
    giveitem expcandyxl
    quest p4c_second_lens done
    say "HARU: Dark Pulse, from the Clan's own TM case. Seems fitting to turn their darkness back on them."
  else
    say "HARU: Clan scout Haru. Eclipse grunts dug into that cave in the west cliff and haul crates in and out all night.\pWe think they're building a second lens, in case the first one fails. If you can, wreck it!"
    if not quest p4c_second_lens started
      quest p4c_second_lens start
    end
  end
end

script p4c_ws_switch_a
  setflag p4c_ws_a
  say "Click! Machinery grinds somewhere to the east. The first gate in front of the ladder sinks into the floor."
end

script p4c_ws_switch_b
  setflag p4c_ws_b
  say "Click! Machinery grinds somewhere to the west. The second gate in front of the ladder sinks into the floor."
end

script p4c_ws_chief
  faceplayer
  say "CHIEF HALLORAN: Who let a child into the Lens Lab?! That lens is three years of my life!"
  battle p4c_ws_chief_halloran
  if result win
    say "CHIEF HALLORAN: Fine! Fine. Do what you like. Umbra has the first lens anyway... I'm going back to my mother's."
    setflag p4c_ws_chief_beaten
    fadeout
    hide el_chief
    fadein
  end
end

script p4c_ws_lens
  if flag p4c_lens_destroyed
    say "Shards of black glass, swept into a neat pile. Someone in Team Eclipse is very tidy."
  elif not flag p4c_ws_chief_beaten
    say "A huge black lens on a steel frame. Chief Halloran glares at you over it."
  else
    say "A huge black lens on a steel frame, still warm from polishing. A crank on the side tilts it..."
    ask "Tip the lens off its frame?"
    if result yes
      shake
      say "CRASH! The backup lens shattered into a thousand black shards!"
      setflag p4c_lens_destroyed
      if quest p4c_second_lens started
        quest p4c_second_lens next
      end
    else
      say "You left the lens alone. For now."
    end
  end
end
