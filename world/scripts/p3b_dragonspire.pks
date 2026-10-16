# Part 3B: Dragonspire City (arrival, gates, NPCs, side quests) and Gym 8 (Ryuga, the Dusk Spire).

# --- Shared --------------------------------------------------------------------
script p3b_nurse
  faceplayer
  say "Welcome to the Pokémon Center! Let me restore your Pokémon to full health."
  healcenter
  say "Your Pokémon are fighting fit. We hope to see you again!"
end

script p3b_shop_dragonspire
  faceplayer
  say "Welcome! Mountain prices, but mountain-strong goods."
  shop p3b_dragonspire
end

script p3b_shop_dragonspire_tm
  faceplayer
  say "TM corner! The Clan's favorite techniques, bottled for travelers."
  shop p3b_dragonspire_tm
end

# --- Arrival and gates ---------------------------------------------------------
script p3b_dragonspire_arrive
  setflag p3b_arrived
  emote ds_lys !
  say "LYS: A traveler from the west road! Welcome to Dragonspire City, home of the Dragon Clan.\pI'm Lys. Sorry, we're all a little jumpy. The sky over Mt. Prism keeps turning purple, and people in black were seen heading south."
  say "LYS: If you're here for the Dusk Spire, Ryuga only accepts challengers with seven Badges. The tower is right up the main avenue."
  quest p3b_story start
end

script p3b_dragonspire_lys
  faceplayer
  if flag p3b_eclipse_defeated
    say "LYS: The sky is blue again! The whole Clan is talking about you, {player}."
  elif flag p3b_badge8
    say "LYS: You beat Ryuga?! Then hurry. Umbral Pass is through the south gate."
  else
    say "LYS: Dragons are proud, but they respect strength. So does Ryuga. Up the avenue, the tall dark tower!"
  end
end

script p3b_gym_door
  if badges >= 7
    setflag p3b_gym_open
  else
    say "A deep voice echoes from the tower: \"Only a trainer bearing seven Badges may climb the Dusk Spire!\""
    move player down
  end
end

script p3b_umbral_gate
  face ds_clanguard right
  if badge 8
    setflag p3b_badge8
    say "CLAN GUARD: The Dusk Badge! Ryuga said you'd come. Umbral Pass is open to you."
  else
    say "CLAN GUARD: Halt! Umbral Pass leads to the sacred summit of Mt. Prism.\pOnly the bearer of the Dusk Badge may pass. Ryuga's orders."
    move player up
  end
end

script p3b_clanguard
  faceplayer
  if flag p3b_eclipse_defeated
    say "CLAN GUARD: The summit is at peace. I can finally sit down. I've been standing for three days."
  elif flag p3b_badge8
    say "CLAN GUARD: Team Eclipse went up the pass at dawn. Go, and may the dragons guide you!"
  else
    say "CLAN GUARD: No one passes without the Dusk Badge. Not even my grandmother. She tried."
  end
end

script p3b_route13_gate
  face ds_officer down
  if flag p3b_badge8
    say "OFFICER: Route 13 is sealed by League order while Team Eclipse is on the mountain.\pRyuga says they went up Umbral Pass, through the south gate."
  else
    say "OFFICER: Sorry, Route 13 is closed. There's Team Eclipse activity on the mountain, and the League won't reopen the road until it's dealt with."
  end
  move player left
end

script p3b_route13_officer
  faceplayer
  if flag p3b_eclipse_defeated
    say "OFFICER: Route 13 is open again, thanks to you! Victory Road and the League are to the east."
  else
    say "OFFICER: Route 13 leads to Victory Road and the League. It's closed until Team Eclipse is stopped."
  end
end

# --- City NPCs -----------------------------------------------------------------
script p3b_dragonspire_fisher
  faceplayer
  if flag p3b_got_superrod
    say "FISHER: Super Rod, deep water, patience. Scalemirror Pond even hides Dratini, if you're lucky!"
  else
    say "FISHER: People say there are no Dragon Pokémon in Scalemirror Pond. People are wrong.\pYou need a real rod to reach the deep ones, though. Here, take my spare!"
    giveitem superrod
    setflag p3b_got_superrod
    say "FISHER: Cast it anywhere there's water. Big catches await!"
  end
end

script p3b_dragonspire_scientist
  faceplayer
  if flag p3b_eclipse_defeated
    say "SCIENTIST: The power spot readings at the Dusk Spire are back to normal. So are Mt. Prism's. Remarkable!"
  else
    say "SCIENTIST: The Dusk Spire stands on a power spot, like the Frostmere Gym. Dynamax works inside!\pRyuga's ace grows to giant size there. And his dragons can Terastallize too. Twice the trouble!"
  end
end

script p3b_center_gentleman
  faceplayer
  say "GENTLEMAN: When a Pokémon Terastallizes, its type changes to its Tera Type. Ryuga's are all Dragon, so Ice and Fairy moves stay reliable."
end

script p3b_elder_lore
  faceplayer
  if flag p3b_eclipse_defeated
    say "ELDER: You saw the Prism Pokémon with your own eyes? Then the old story is true...\pIt devours light only when the world forgets to share it. Thank you for reminding it."
  else
    say "ELDER: Long ago, a creature of black crystal fell upon Mt. Prism, starving for light. It drank the sun until the land froze in endless dusk.\pOur ancestors offered it the mountain's crystals, and it fell asleep within them. We call it the Prism Pokémon."
    say "ELDER: If someone were to wake it hungry... I shudder to think. Those people in black carry a great lens. I fear what they intend."
  end
end

script p3b_relearner
  faceplayer
  say "MEMORY SAGE: Every move a Pokémon has learned stays in its heart. I can help it remember. Shall we try?"
  moverelearner
end

script p3b_shrine_tablet
  say "The tablet reads: \"When light grows greedy, the Prism wakes to take it back. When light is shared, the Prism sleeps.\""
end

# --- Side quest: the Dragon Trial (Elder Draca) --------------------------------
script p3b_draca
  faceplayer
  if quest p3b_dragon_trial done
    say "DRACA: Raise that Dragonair with care. One day it will carry you above the clouds."
  else
    say "ELDER DRACA: Welcome to the Dragon Shrine. I am Draca, keeper of the Clan's oldest knowledge.\pThose who pass the Dragon Trial may take a dragon of the Clan into the world."
    ask "DRACA: Will you take the Dragon Trial?"
    if result yes
      if not quest p3b_dragon_trial started
        quest p3b_dragon_trial start
      end
      setvar p3b_trial_score 0
      choice "Which type is super effective against Dragon?" "Fire" "Ice" "Water"
      if result 1
        addvar p3b_trial_score 1
      end
      choice "Into what does Dragonair evolve?" "Dragonite" "Salamence" "Garchomp"
      if result 0
        addvar p3b_trial_score 1
      end
      choice "Which type is immune to Dragon-type moves?" "Steel" "Fairy" "Ghost"
      if result 1
        addvar p3b_trial_score 1
      end
      if var p3b_trial_score >= 3
        say "DRACA: Three of three. Splendid! The dragons approve of you.\pThis Dragonair was raised in the shrine. It knows a move few dragons ever learn."
        givemon dragonair 50 moves=extremespeed,dragonrush,aquatail,dragondance
        if result 1
          quest p3b_dragon_trial done
        else
          say "DRACA: Your party and your boxes are full. Make room, then return."
        end
      else
        say "DRACA: {var:p3b_trial_score} of three. Study well and return. The Trial will wait for you."
      end
    else
      say "DRACA: Knowledge is patient. Return when you are ready."
    end
  end
end

# --- Side quest: the Scale Collector -------------------------------------------
script p3b_scale_collector
  faceplayer
  if quest p3b_scale_trade done
    say "COLLECTOR: Look at my display case! Three Dragon Scales, glittering like a sunset. Thank you!"
  elif item dragonscale >= 3
    say "COLLECTOR: Are those... three Dragon Scales?! Please, let me have them for my collection!\pIn exchange, take this. It was my late husband's. He was very fast."
    takeitem dragonscale 3
    giveitem choicescarf
    quest p3b_scale_trade done
  else
    say "COLLECTOR: I collect Dragon Scales! I need three for my display case.\pThey turn up around Dragonspire and the mountains south of town. Dragons shed them in odd places."
    if not quest p3b_scale_trade started
      quest p3b_scale_trade start
    end
  end
end

# --- Side quest: the trade (new `trade` command) ------------------------------
script p3b_trader
  faceplayer
  if flag p3b_traded_seadra
    say "SAILOR: My Seaking reminds me of my fishing days. Take good care of TIDES!"
  else
    say "SAILOR: Ahoy! I retired up here for the mountain air, but I miss the water.\pI'd love a Seaking from Scalemirror Pond. I'll trade you my Seadra, TIDES. It's holding a Dragon Scale!"
    if not quest p3b_skyfall_trade started
      quest p3b_skyfall_trade start
    end
    if party has seaking
      ask "SAILOR: Is that a Seaking? Trade it for TIDES?"
      if result yes
        trade seaking seadra 50 nick=TIDES item=dragonscale
        if result 1
          setflag p3b_traded_seadra
          quest p3b_skyfall_trade done
          say "SAILOR: Ha ha! What a beauty! TIDES loves battling. Treat it well!"
        else
          say "SAILOR: Changed your mind? No problem, matey."
        end
      end
    end
  end
end

# --- Gym 8: the Dusk Spire -----------------------------------------------------
script p3b_dgym_guide
  faceplayer
  if badge 8
    say "Yo, champ-to-be! You conquered the Dusk Spire!"
  else
    say "Yo, challenger! This tower floats over a bottomless dark. Step off the stone and you'll find yourself back at the door!\pGlowing switches raise bridges. Most of them, anyway. Ice and Fairy moves are your friends up there."
  end
end

script p3b_dgym_fall
  say "You lost your footing in the dark!"
  fadeout
  warp dragonspire_gym 8 18 up
  fadein
end

script p3b_dgym_trap
  say "Click! The switch crumbles under your feet... A trap!"
  fadeout
  warp dragonspire_gym 8 18 up
  fadein
end

script p3b_dgym_sw1
  setflag p3b_dg_b1
  say "Click! A stone bridge rose from the dark to the west ledge."
end

script p3b_dgym_sw2
  setflag p3b_dg_b2
  say "Click! A bridge stretched across the gap to the east."
end

script p3b_dgym_sw3
  setflag p3b_dg_b3
  say "Click! A bridge rose toward the upper landing."
end

script p3b_dgym_sw4
  setflag p3b_dg_b4
  say "Click! A bridge rose toward the west roost."
end

script p3b_dgym_sw5
  setflag p3b_dg_b5
  say "Click! A long bridge spanned the chasm to the east roost."
end

script p3b_dgym_sw6
  setflag p3b_dg_b6
  say "Click! The final bridge rose to Ryuga's crown."
end

script p3b_ryuga
  faceplayer
  if badge 8
    if flag p3b_eclipse_defeated
      say "RYUGA: The summit sleeps, and the sky is clear. The Clan owes you a debt, {player}.\pGo east. Victory Road and the League await a trainer like you."
    else
      say "RYUGA: Umbral Pass is through the south gate. Team Eclipse is already climbing. Go!"
    end
  else
    say "RYUGA: So you crossed the dark. I am Ryuga, master of the Dusk Spire.\pDragons are born in storms and raised in twilight. Show me that your bond can fly through both!"
    battle p3b_leader_ryuga
    if result win
      say "RYUGA: Magnificent. You and your Pokémon fly as one. Take the Dusk Badge!"
      badge 8
      setflag p3b_badge8
      say "RYUGA: With it, every Pokémon will obey you, and your journey to the League is open."
      tm dragonpulse
      giveitem dragoniumz
      say "RYUGA: Dragon Pulse, and a Dragonium Z for your Z-Ring.\pNow listen. My scouts report Team Eclipse marching up Umbral Pass toward Prism Summit, hauling some great lens. I have told the south gate to let you pass."
      if not quest p3b_story started
        quest p3b_story start
      end
      quest p3b_story next
    end
  end
end
