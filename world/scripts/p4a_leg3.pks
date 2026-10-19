# Expansion E1, leg 3 (Gym 2 -> Gym 3): Glimmer Cave, Lumen Catacombs, Solar Ridge, Ridge Station, Sparkstone
# Grotto, Dot (rematch), the Voltara trade and catch-count aide #3.

# ---------------------------------------------------------------- Route 6 / Glimmer Cave
script p4a_r6_cave_sign
  say "GLIMMER CAVE\pDanger: no lights inside. Use Flash."
end

script p4a_gc_sign
  say "Scratched into the rock:\p\"Crystals glow for those who bring their own light.\" - a miner"
end

script p4a_gc_crystal
  say "A huge crystal juts from the wall. Deep inside, a faint light pulses... like a heartbeat."
end

# ---------------------------------------------------------------- Lumen Catacombs: order puzzle
script p4a_lc_clue
  say "A mural of the sun's journey is carved here:\p\"It wakes in the east, stands tall in the north, sleeps in the west, and rests beneath the earth.\""
end

script p4a_lc_mural
  say "A great mural of a radiant Pokémon with prism wings. Unown letters circle it.\pThey spell... \"LIGHT RETURNS TO THE MOUNTAIN.\""
end

script p4a_lc_stone_e
  if var p4a_cat_seq <= 1
    if var p4a_cat_seq == 0
      say "The stone of dawn began to glow!"
    end
    setvar p4a_cat_seq 1
  else
    call p4a_lc_reset
  end
end

script p4a_lc_stone_n
  if var p4a_cat_seq == 1
    setvar p4a_cat_seq 2
    say "The stone of noon began to glow!"
  elif var p4a_cat_seq != 2
    call p4a_lc_reset
  end
end

script p4a_lc_stone_w
  if var p4a_cat_seq == 2
    setvar p4a_cat_seq 3
    say "The stone of dusk began to glow!"
  elif var p4a_cat_seq != 3
    call p4a_lc_reset
  end
end

script p4a_lc_stone_s
  if var p4a_cat_seq == 3
    setvar p4a_cat_seq 4
    setflag p4a_cat_open
    sfx switch
    shake
    say "The stone of night began to glow! All four stones blazed with light...\pA wall to the north rumbled open!"
  else
    call p4a_lc_reset
  end
end

script p4a_lc_reset
  if var p4a_cat_seq > 0
    setvar p4a_cat_seq 0
    say "All the glowing stones went dark..."
  end
end

# ---------------------------------------------------------------- Voltara City / Solar Ridge
script p4a_vc_ridge_sign
  say "SOLAR RIDGE - north\nRidge Station"
end

script p4a_sr_sign
  say "SOLAR RIDGE\pVoltara's backup power comes from these panels. Please don't climb on them!"
end

script p4a_engineer
  faceplayer
  if quest p4a_machine_part done
    say "WATTS: The relay hums like new! If the plant ever goes dark again, Voltara will still have light."
  elif item machinepart
    say "WATTS: That's my Machine Part! You found it in Glimmer Cave? In the dark? Incredible!"
    say "WATTS: Let me install it... There! The relay's back online."
    say "WATTS: Here, I've been raising this little one at the station. It's got more spark than I can handle!"
    givemon pawmi 20
    if result yes
      takeitem machinepart
      quest p4a_machine_part done
    else
      say "WATTS: Oh, your party and boxes are full. Make some room and come back, the Machine Part can wait."
    end
  else
    say "WATTS: I'm the engineer at Ridge Station. I dropped a Machine Part in Glimmer Cave, north of Route 6."
    say "WATTS: Without it, the solar relay can't send power down to Voltara. And it's pitch black in there!"
    if not quest p4a_machine_part started
      quest p4a_machine_part start
    end
  end
end

script p4a_station_worker
  faceplayer
  if flag p2_power_restored
    say "The power plant's running again? Great! Our panels were barely keeping the lights on."
  else
    say "Team Eclipse is draining the power plant. Our panels are all that keep Voltara's hospital lit!"
  end
end

script p4a_rg_crystal
  say "Crystals crackle with tiny sparks. Touching one makes your hair stand on end!"
end

script p4a_vc_trader
  faceplayer
  if flag p4a_traded_blitzle
    say "TRADER: Electrike are so loyal! How's Zappy? She can run faster than a bike!"
  else
    say "TRADER: I'm raising a Blitzle, but I've always wanted an Electrike. They're so loyal!"
    if not quest p4a_zappy_trade started
      quest p4a_zappy_trade start
    end
    ask "Would you trade an Electrike for my Blitzle, Zappy?"
    if result yes
      trade electrike blitzle 22 nick=Zappy ot=VOLTA_FAN
      if result 1
        setflag p4a_traded_blitzle
        quest p4a_zappy_trade done
        say "TRADER: Yay! Zappy loves to gallop. Give her room to run!"
      else
        say "TRADER: No Electrike? They crackle around in the grass on Route 6."
      end
    else
      say "TRADER: Okay... Zappy and I will keep waiting!"
    end
  end
end

script p4a_dex_aide_3
  faceplayer
  if flag p4a_dex_reward_3
    say "AIDE: Thirty kinds and counting! Prof. Linden says your Pokédex is one of the best he's seen."
  elif caughtcount >= 30
    say "AIDE: Thirty kinds of Pokémon caught? Prof. Linden asked me to give you his best reward."
    giveitem abilitycapsule
    giveitem expcandym 3
    setflag p4a_dex_reward_3
    say "AIDE: An Ability Capsule switches a Pokémon to its other regular Ability. Use it wisely!"
  else
    say "AIDE: I'm Prof. Linden's aide in Voltara. Thirty kinds caught earns his best reward!\pThe ridge north of the city is full of Pokémon you won't find anywhere else."
  end
end

# ---------------------------------------------------------------- Route 6: Dot (phone-style rematch)
script p4a_dot
  faceplayer
  if not trainer p4a_dot_1
    say "DOT: Zap! Zap! My team is the zappiest on Route 6! Battle me!"
    battle p4a_dot_1
    say "DOT: Wow, you've got spark! I'll be training right here. Come back after more badges for a rematch!"
  elif badges >= 8 and not trainer p4a_dot_4
    say "DOT: Eight badges?! This is it, {player}. My strongest team, fully charged!"
    battle p4a_dot_4
    say "DOT: That was electric! Take this. My Ampharos found it shining by the power lines."
    giveitem ppmax
  elif badges >= 6 and not trainer p4a_dot_3
    say "DOT: Six badges! I've been storing up voltage just for you!"
    battle p4a_dot_3
    say "DOT: Shorted out again! Here, this Magnet came from Voltara's junkyard."
    giveitem magnet
  elif badges >= 4 and not trainer p4a_dot_2
    say "DOT: {player}! My Pikachu evolved! Rematch time!"
    battle p4a_dot_2
    say "DOT: Still zappier than me... Here, take these!"
    giveitem thunderstone
  elif trainer p4a_dot_4
    say "DOT: I'm going to work at the power plant someday. My team can run the whole city!"
  else
    say "DOT: I'm still charging up! Come back after you earn a couple more badges."
  end
end

# ---------------------------------------------------------------- Route 5 east: Seabreeze Cove
script p4a_r5_cove_sign
  say "SEABREEZE COVE - east\nTide pools and sandy beaches."
end

script p4a_sb_sign
  say "SEABREEZE COVE\pPlease take your litter home. And your Krabby. Especially your Krabby."
end

script p4a_shell_collector
  faceplayer
  if quest p4a_shell_bell done
    say "COLLECTOR: My Big Pearl gleams on the shelf every morning. Thank you again."
  elif item bigpearl
    say "COLLECTOR: Oh my! Is that a Big Pearl? I've searched this beach for one for years!"
    ask "Would you trade it for a Shell Bell?"
    if result yes
      takeitem bigpearl
      giveitem shellbell
      quest p4a_shell_bell done
      say "COLLECTOR: A Pokémon holding a Shell Bell heals a little whenever it lands a hit. Splendid, isn't it?"
    else
      say "COLLECTOR: Ah, I understand. A Big Pearl is a treasure. If you change your mind..."
    end
  else
    say "COLLECTOR: I collect treasures from the sea. Shells, sea glass, pearls... but never a Big Pearl!"
    say "COLLECTOR: If you find one, I'll trade you my finest Shell Bell for it.\pThey wash up in sea caves and on sandbars, I hear."
    if not quest p4a_shell_bell started
      quest p4a_shell_bell start
    end
  end
end

script p4a_old_salt
  faceplayer
  say "OLD SALT: Fifty years I sailed between Port Tidewater and the Coral Isles. Fifty years!"
  say "OLD SALT: Now they only let Champions on the ferry. Bah! In my day, any kid with a Magikarp could ride."
end

# ---------------------------------------------------------------- Voltmire Marsh
script p4a_vm_sign
  say "VOLTMIRE MARSH\pCaution: deep pools. Do not touch the power line pylons."
end

script p4a_vm_hermit
  faceplayer
  if flag p2_power_restored
    say "HERMIT: The lines are humming again. The Tynamo are happy. Me too, I suppose."
  else
    say "HERMIT: The power lines went quiet a while ago. The Tynamo in the pools have stopped glowing."
    say "HERMIT: Something in Voltara is drinking all the power. Mark my words, it's those folks in the dark uniforms."
  end
end
