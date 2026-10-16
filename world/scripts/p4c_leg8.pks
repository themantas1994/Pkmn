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
