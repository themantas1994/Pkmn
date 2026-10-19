# Expansion E3, leg 7: Rime Hollow (Power Spot survey, Snorlax), Tide Grotto / S.S. Halcyon (captain's log,
# Dhelmise), Margit the heart-scale tutor, Route 10 geologist.

# ------------------------------------------------------------------ Power Spot survey (Hollis's Lodge)
script p4c_hl_brynn
  faceplayer
  if flag p4c_hollis_done
    say "BRYNN: Hollis is writing up our survey. Twelve pages so far. Eleven of them are about that Snorlax."
  elif flag p4c_hollis_survey
    if flag p4c_rime_read1 and flag p4c_rime_read2 and flag p4c_rime_read3
      say "BRYNN: All three readings! Look at these numbers... the Power Spot's core is right under the den.\pHollis will be thrilled. Here, these are from both of us."
      giveitem weaknesspolicy
      giveitem dynamaxcandy 3
      setflag p4c_hollis_done
      quest p4c_power_survey done
      say "BRYNN: A Weakness Policy loves a Dynamaxed Pokémon. Big body, big hits, bigger comeback!"
    else
      say "BRYNN: The three vents are in Rime Hollow: one on the first floor, one by the frozen lake, one in the den.\pJust step on the glowing ones. The scope does the rest."
    end
  elif flag p3a_got_dynamaxband
    say "BRYNN: You're the one with the Dynamax Band! I'm Brynn, Hollis's field assistant.\pWe think Frostmere's Power Spot starts in Rime Hollow, the ice cave north of town. But the ice floors... I keep sliding into walls."
    ask "BRYNN: Could you take readings at the three glowing vents inside?"
    if result yes
      setflag p4c_hollis_survey
      quest p4c_power_survey start
      say "BRYNN: Thank you! Step on each glowing vent and the scope records it. The cave is at the north edge of town."
    else
      say "BRYNN: No worries. The ice isn't going anywhere. Neither am I, apparently."
    end
  else
    say "BRYNN: Hollis is the expert on Power Spots. I'm the expert on carrying Hollis's equipment."
  end
end

script p4c_rime_reading1
  say "The vent glows red. Galar particles swirl up around your feet!"
  say "The Power Spot Scope beeped. Reading recorded!"
  setflag p4c_rime_read1
  quest p4c_power_survey next
end

script p4c_rime_reading2
  say "Red light seeps up through the ice. The air tastes like a thunderstorm."
  say "The Power Spot Scope beeped. Reading recorded!"
  setflag p4c_rime_read2
  quest p4c_power_survey next
end

script p4c_rime_reading3
  say "The whole den pulses red. The scope's needle spins off the dial!"
  say "The Power Spot Scope beeped. Reading recorded!"
  setflag p4c_rime_read3
  quest p4c_power_survey next
end

script p4c_rime_snorlax
  say "A gigantic Snorlax is fast asleep in the middle of the den. Red light pours from the ground beneath it..."
  ask "Wake it up?"
  if result yes
    say "You gave Snorlax a poke. Then another. Then a really big one!"
    cry snorlax
    say "Snorlax woke up, swollen with Dynamax energy, and it's furious!"
    wildbattle snorlax 50 gmax norun
    if result caught or result defeated
      setflag p4c_snorlax_done
      hide rd_snorlax
    else
      say "Snorlax yawned enormously and went straight back to sleep."
    end
  else
    say "It's snoring so loudly that the icicles rattle."
  end
end

# ------------------------------------------------------------------ Margit, the heart-scale tutor (Frostmere)
script p4c_fm_margit
  faceplayer
  if quest p4c_heart_tutor done
    say "MARGIT: Back for a lesson? Pick a Pokémon and I'll show it something special."
    movetutor p4c_margit
  elif item heartscale
    say "MARGIT: Is that a Heart Scale? Oh, how it shines! The old tuition, paid in full."
    takeitem heartscale 1
    quest p4c_heart_tutor done
    say "MARGIT: From now on, my lessons are yours whenever you like. Let's start right away."
    movetutor p4c_margit
  else
    say "MARGIT: I taught moves to half the Pokémon in the north, back when my knees still worked.\pThe old custom is one Heart Scale as tuition. They wash up on Route 11 and hide in sea caves."
    if not quest p4c_heart_tutor started
      quest p4c_heart_tutor start
    end
  end
end

# ------------------------------------------------------------------ The S.S. Halcyon (Sea Cottage, Tide Grotto)
script p4c_r11_captain
  faceplayer
  if flag p4c_halcyon_done
    say "MORREL: I read the log every night now. The Halcyon's last voyage, in my own handwriting. Thank you, friend."
  elif flag p4c_got_halcyon_log
    say "MORREL: That's... my logbook! You went down into the hold for this?"
    say "MORREL: \"Day 212. Sky black at noon. The crystal cargo glowing like coals. Making for the grotto to wait out the storm.\"\pThe crystals... We carried them from Mt. Prism for the Dragon Clan. And that day, the sun simply went out."
    if not quest p4c_halcyon started
      quest p4c_halcyon start
    end
    tm liquidation
    giveitem leftovers
    setflag p4c_halcyon_done
    quest p4c_halcyon done
    say "MORREL: Liquidation, a sailor's move. And Leftovers; my old Lapras never sailed without some. Fair winds, friend!"
  elif flag p4c_halcyon_quest
    say "MORREL: Tide Grotto is in the cliff of the southwest islet. My strongbox sank with the hold. Use Flash, it's black as ink down there."
  else
    say "MORREL: Captain Morrel, retired. I skippered the S.S. Halcyon until she ran aground in Tide Grotto, forty years back.\pThe day of the Dark Noon, folk call it. The sun went out like a lamp."
    say "MORREL: My logbook was locked in the strongbox in the hold. If you're ever down there... I'd dearly love to read it again."
    setflag p4c_halcyon_quest
    quest p4c_halcyon start
  end
end

script p4c_wreck_strongbox
  if flag p4c_got_halcyon_log
    say "The captain's strongbox. Empty now, apart from seaweed and one very small crab."
  else
    say "A rusted strongbox engraved \"M. MORREL, MASTER\". The lid creaks open..."
    say "Inside: a logbook wrapped in oilcloth, still dry, and a heavy gold relic."
    giveitem relicgold
    setflag p4c_got_halcyon_log
    if quest p4c_halcyon started
      quest p4c_halcyon next
    end
    say "You took the Halcyon's logbook. Someone ought to return it to its captain."
  end
end

script p4c_wreck_anchor
  say "A huge rusted anchor, tangled in seaweed... The seaweed is breathing."
  cry dhelmise
  wildbattle dhelmise 47
  if result caught or result defeated
    setflag p4c_dhelmise_done
    hide wh_anchor
  else
    say "The anchor sank back into the black water. It'll be back."
  end
end

script p4c_wreck_logpage
  say "A torn notice is nailed to the wall: \"Passengers are reminded that ghosts are NOT included in the ticket price.\" Someone underlined NOT three times."
end

# ------------------------------------------------------------------ Route 10
script p4c_r10_geologist
  faceplayer
  say "GEOLOGIST: Ashfall's volcano and Crystal Cavern share the same roots. Heat below, crystals above!\pThe old records say every crystal in the cavern glowed red for a whole day, forty years ago. The day of the Dark Noon."
end

# ------------------------------------------------------------------ Crystal thief (Crystal Cavern B1 / Geode Hollow)
script p4c_ccb_porter
  faceplayer
  if flag p4c_crystal_thief_done
    say "PORTER: The crates are full again and Quartz is humming. That's the happiest sound in this cave."
  elif flag p4c_straggler_beaten
    say "PORTER: You got the sack back? Every shard's here! Quartz is going to faint. Happily, I mean."
    if not quest p4c_crystal_thief started
      quest p4c_crystal_thief start
    end
    tm terablast
    giveitem steelterashard 3
    setflag p4c_crystal_thief_done
    quest p4c_crystal_thief done
    say "PORTER: Tera Blast takes on its user's Tera Type once it Terastallizes. Quartz says it's the purest Tera move there is."
  elif flag p3a_got_teraorb
    say "PORTER: Someone's been stealing Tera Shards from our sample crates in Geode Hollow, below the boulder room upstairs.\pIf you can push that boulder, could you take a look? Bring back what you find!"
    if not quest p4c_crystal_thief started
      quest p4c_crystal_thief start
    end
  else
    say "PORTER: I haul Quartz's samples. Heavy work, but the crystals are pretty. Mind the grunts by the tunnel!"
  end
end

script p4c_gh_straggler
  faceplayer
  if flag p4c_straggler_beaten
    say "Nothing left here but crowbar marks."
  else
    say "GRUNT: Huh? A kid? Down HERE? This sack of shards is Team Eclipse property now!"
    battle p4c_gh_grunt_dorran
    if result win
      say "GRUNT: Fine! Take your rocks! I'm going to go find the others. They're... somewhere north. Probably."
      setflag p4c_straggler_beaten
      if quest p4c_crystal_thief started
        quest p4c_crystal_thief next
      end
      fadeout
      hide gh_straggler
      fadein
      say "You recovered the sack of stolen Tera Shards!"
    end
  end
end
