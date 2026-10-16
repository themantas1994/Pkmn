# Expansion E1, leg 2 (Gym 1 -> Gym 2): Sunberry Orchard, Tidewater Cape, the lighthouse and its sea grotto,
# Cobblecrest B2F, Teo (rematch), the Tidewater trade and catch-count aide #2.

# ---------------------------------------------------------------- Sunberry Orchard
script p4a_or_sign
  say "SUNBERRY ORCHARD\pPlease pick only what you can eat. The Skwovet are counting."
end

script p4a_orchard_keeper
  faceplayer
  if quest p4a_orchard_fertilizer done
    say "KEEPER: The trees have never looked better! That Big Mushroom worked wonders."
  elif item bigmushroom
    say "KEEPER: Is that a Big Mushroom? Ground up, it makes the finest fertilizer there is!"
    ask "Would you part with it? I'll trade you something special."
    if result yes
      takeitem bigmushroom
      say "KEEPER: Bless you! Here. A Pokémon holding these Leftovers nibbles on them to heal a little every turn."
      giveitem leftovers
      quest p4a_orchard_fertilizer done
    else
      say "KEEPER: I understand. Mushrooms are precious. If you change your mind, you know where I am."
    end
  else
    say "KEEPER: Welcome to Sunberry Orchard. My trees have been tired this year...\pWhat they need is a Big Mushroom. Whisperwood's mushroom patches sometimes hide one."
    if not quest p4a_orchard_fertilizer started
      quest p4a_orchard_fertilizer start
    end
  end
end

script p4a_orchard_wife
  faceplayer
  say "Long walk from Brindle, dear? Let me pour your Pokémon some berry tea."
  heal
  say "There, all better! Come back anytime."
end

# ---------------------------------------------------------------- Port Tidewater / Tidewater Cape
script p4a_tw_cape_sign
  say "TIDEWATER CAPE - north\nTidewater Lighthouse"
end

script p4a_cp_sign
  say "TIDEWATER CAPE\pThe lighthouse has guided ships into Port Tidewater for two hundred years."
end

script p4a_cp_kid
  faceplayer
  if quest p4a_lamp_key done
    say "The lighthouse is shining again! Ships can find the harbor at night now."
  else
    say "I saw a Wingull fly into the cliffs with something shiny in its beak!\pThere's a cave up the beach, behind the rocks. It went in there!"
  end
end

script p4a_tw_trader
  faceplayer
  if flag p4a_traded_clobbopus
    say "SAILOR: My Machop does push-ups on deck all day. Is Inky behaving?"
  else
    say "SAILOR: I've got a Clobbopus that keeps sneaking into the cargo hold. I'd rather have a deckhand!"
    if not quest p4a_inky_trade started
      quest p4a_inky_trade start
    end
    ask "Trade me a Machop for my Clobbopus, Inky?"
    if result yes
      trade machop clobbopus 15 nick=Inky ot=SKIPPER
      if result 1
        setflag p4a_traded_clobbopus
        quest p4a_inky_trade done
        say "SAILOR: Ha ha! Welcome aboard, Machop! Inky's yours. Mind the tentacles!"
      else
        say "SAILOR: No Machop? They punch rocks all over Route 4 in the morning and daytime."
      end
    else
      say "SAILOR: Suit yourself. Inky's not going anywhere. Except the cargo hold."
    end
  end
end

script p4a_dex_aide_2
  faceplayer
  if flag p4a_dex_reward_2
    say "AIDE: Prof. Linden's third aide waits in Voltara City for trainers with thirty kinds caught. Keep going!"
  elif caughtcount >= 20
    say "AIDE: Twenty kinds of Pokémon! The Professor will want to hear about this."
    say "AIDE: Here's your reward. A Pokémon holding an Amulet Coin doubles the prize money you earn!"
    giveitem amuletcoin
    setflag p4a_dex_reward_2
  else
    say "AIDE: I'm another of Prof. Linden's aides. Show me a Pokédex with twenty kinds caught and you'll get a reward.\pTry fishing. Lots of Pokémon only bite on a hook!"
  end
end

# ---------------------------------------------------------------- Tidewater Lighthouse
script p4a_lh_guide
  faceplayer
  if quest p4a_lamp_key done
    say "The lamp is lit again, thanks to you. Two hundred years and it's never been dark this long!"
  else
    say "Welcome to Tidewater Lighthouse. The stairs are steep, so watch your step!\pThe keeper's on the second floor. He's had a rough week."
  end
end

script p4a_lh_lockdoor
  if flag p4a_lh_unlocked
    say "The door to the lamp room stands open."
  else
    say "A heavy door to the lamp room. It's locked tight."
  end
end

script p4a_lh_keeper
  faceplayer
  if item basementkey
    say "KEEPER: Is that... my key! The one with the BASEMENT tag? Long story. It opens the lamp room."
    takeitem basementkey
    say "KEEPER: You found it in the sea cave? That rascal Wingull! Come on, let's light the lamp!"
    setflag p4a_lh_unlocked
    quest p4a_lamp_key 1
    hide p4a_lh_keeper
  else
    say "KEEPER: Oh, dear. A Wingull snatched my key right off the gallery railing.\pWithout it I can't get into the lamp room, and the ships can't see the harbor at night!"
    say "KEEPER: My granddaughter says it flew down toward the beach below the cliffs."
    if not quest p4a_lamp_key started
      quest p4a_lamp_key start
    end
  end
end

script p4a_lh_keeper_top
  faceplayer
  if flag p4a_lh_reward
    say "KEEPER: Look at that beam sweep across the bay. Beautiful, isn't it?"
  elif flag p4a_lh_unlocked
    say "KEEPER: There! The lamp is lit. Every ship in the bay can see Port Tidewater again."
    say "KEEPER: I'm not much of a battler, but I keep this for emergencies. It's yours."
    tm chargebeam
    setflag p4a_lh_reward
    quest p4a_lamp_key done
    say "KEEPER: Charge Beam crackles with electricity. Handy against Marina's Water Pokémon, I'd wager!"
  else
    say "KEEPER: How did you get up here?"
  end
end

script p4a_lh_window
  say "The whole bay glitters below. Far to the southeast, you can just make out islands on the horizon."
end

# ---------------------------------------------------------------- Cobblecrest B2F
script p4a_cc2_switch1
  sfx switch
  setflag p4a_cc2_gate1
  shake
  say "The glowing stone sank into the floor. A rumble echoed from the southwest!"
end

script p4a_cc2_switch2
  sfx switch
  setflag p4a_cc2_gate2
  shake
  say "Another rumble! Something heavy slid open to the east."
end

script p4a_cc2_miner
  faceplayer
  if flag p4a_cc2_gate2
    say "OLD MINER: You opened the vault? Ha! My grandpa sealed it when the mine closed.\pWhatever's inside, it's yours now."
  else
    say "OLD MINER: This lake is the heart of Cobblecrest. We miners dug around it for a hundred years.\pThe vault's sealed by two stones. One's way up in the northeast corner, past the lake."
  end
end

# ---------------------------------------------------------------- Route 4: Teo (phone-style rematch)
script p4a_teo
  faceplayer
  if not trainer p4a_teo_1
    say "TEO: You there! A trainer who walks Route 4 should be tough as granite. Prove it!"
    battle p4a_teo_1
    say "TEO: Solid! I'll be climbing all over Tessera. Come find me here after a few more badges and we'll go again!"
  elif badges >= 7 and not trainer p4a_teo_4
    say "TEO: {player}! Seven badges! My team has been waiting for this. One last climb!"
    battle p4a_teo_4
    say "TEO: You stood firmer than any mountain. Take this. I chipped it off a cave wall."
    giveitem starpiece
  elif badges >= 5 and not trainer p4a_teo_3
    say "TEO: Five badges, eh? My Onix has gotten tough as steel. Literally! Let's go!"
    battle p4a_teo_3
    say "TEO: Ha! Here, a little something from Mt. Prism."
    giveitem hardstone
  elif badges >= 3 and not trainer p4a_teo_2
    say "TEO: You're back! And with three badges! My team's evolved. Rematch!"
    battle p4a_teo_2
    say "TEO: You've grown! Here. I found this Nugget on the ridge."
    giveitem nugget
  elif trainer p4a_teo_4
    say "TEO: Every mountain in Tessera, climbed. Except you. Ha ha!"
  else
    say "TEO: Still training! Come back once you've earned a couple more badges."
  end
end
