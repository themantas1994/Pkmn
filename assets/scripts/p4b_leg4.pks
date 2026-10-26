# Expansion E2, leg 4 (Gym 3 -> Gym 4): Quarrytown's Old Shaft, the Sol Catacombs and Strata Gulch.

# --- Quarrytown: old Bryn and Shaft No. 3 -------------------------------------------
script p4b_bryn
  faceplayer
  if quest p4b_canary_lamp done
    say "BRYN: Tam's lamp sits on my mantel now. First time in forty years I sleep without dreaming of the dark."
  elif flag p4b_found_lamp
    say "BRYN: That's... that's Tam's lamp. My partner's. We left it burning the day the shaft fell in.\pYou went all the way down to the vault for an old man's keepsake. The Guild gave me this when I retired. It's yours."
    tm stealthrock
    giveitem expcandym 2
    quest p4b_canary_lamp done
    say "BRYN: Stealth Rock. Sharp stones float around the foe's side, and every Pokémon they send out gets a nasty surprise."
  elif quest p4b_canary_lamp started
    say "BRYN: The lamp should be in the deepest gallery, past the flooded level. You'll need Strength and Flash, and the Badges that unlock them."
  else
    say "BRYN: Shaft No. 3. I worked it thirty years, until the day it caved in.\pNobody was hurt, thank the stones. But my partner Tam's lamp is still down in the vault, and Tam's long gone now.\pIf you're heading in, would you look for it? I'd like to keep something of his."
    quest p4b_canary_lamp start
  end
end

script p4b_mine_explorer
  faceplayer
  if flag p4b_found_lamp
    say "KID: You found a glowing lamp in the vault?! I knew it! Grandpa Bryn's stories are true!"
  else
    say "KID: Grandpa Bryn says the No. 3 crew left a lamp burning when the shaft collapsed.\pThe ladder's in the north room, but it's so dark down there... Use Flash, okay?"
  end
end

script p4b_mine_lamp
  say "An old brass miner's lamp. The initials \"T.H.\" are scratched into the base... and somehow it's still glowing faintly."
  setflag p4b_found_lamp
  hide p4b_lamp
  if quest p4b_canary_lamp started
    quest p4b_canary_lamp next
  end
  say "{player} took the lamp. Old Bryn in Quarrytown would want to see this."
end

script p4b_mine_fossil_skull
  say "A Skull Fossil, half-buried in amber-colored stone. It's the head of an ancient Pokémon!"
  ask "Take the Skull Fossil?"
  if result yes
    giveitem skullfossil
    call p4b_mine_fossil_taken
  end
end

script p4b_mine_fossil_armor
  say "An Armor Fossil, smooth as a shield. An ancient Pokémon wore this face plate!"
  ask "Take the Armor Fossil?"
  if result yes
    giveitem armorfossil
    call p4b_mine_fossil_taken
  end
end

script p4b_mine_fossil_taken
  setflag p4b_mine_fossil
  shake
  hide p4b_fossil_skull
  hide p4b_fossil_armor
  say "Loose rock tumbled down and buried the other fossil! One fossil each, as the old miners' rule goes."
end

# --- Fossil Lab: the aide revives the fossils E2 adds ------------------------------------
script p4b_fossil_aide
  faceplayer
  if item skullfossil
    say "AIDE: A Skull Fossil! The boss lets me run the small machine now. Here goes..."
    givemon cranidos 30
    if result yes
      takeitem skullfossil
      call p4b_fossil_aide_done
    else
      call p4b_fossil_aide_full
    end
  elif item armorfossil
    say "AIDE: An Armor Fossil! Stand back, the small machine gets a little smoky..."
    givemon shieldon 30
    if result yes
      takeitem armorfossil
      call p4b_fossil_aide_done
    else
      call p4b_fossil_aide_full
    end
  elif item domefossil
    say "AIDE: A Dome Fossil! These are my favorite. Here goes..."
    givemon kabuto 30
    if result yes
      takeitem domefossil
      call p4b_fossil_aide_done
    else
      call p4b_fossil_aide_full
    end
  elif item helixfossil
    say "AIDE: A Helix Fossil! Some people worship these, you know. Here goes..."
    givemon omanyte 30
    if result yes
      takeitem helixfossil
      call p4b_fossil_aide_done
    else
      call p4b_fossil_aide_full
    end
  elif item clawfossil
    say "AIDE: A Claw Fossil! Sharp... Okay, into the machine..."
    givemon anorith 30
    if result yes
      takeitem clawfossil
      call p4b_fossil_aide_done
    else
      call p4b_fossil_aide_full
    end
  elif item rootfossil
    say "AIDE: A Root Fossil! It's like a plant and a Pokémon at once. Here goes..."
    givemon lileep 30
    if result yes
      takeitem rootfossil
      call p4b_fossil_aide_done
    else
      call p4b_fossil_aide_full
    end
  else
    say "AIDE: The boss handles Jaw, Sail and Old Amber. I can do Skull, Armor, Dome, Helix, Claw and Root fossils!\pThe old Quarrytown shaft and the gulch off Route 8 are full of fossils, they say."
  end
end

script p4b_fossil_aide_full
  say "AIDE: Your party and your boxes are full! Make some room first, and bring the fossil back. It's safe with you."
end

script p4b_fossil_aide_done
  say "AIDE: It worked! ...It actually worked! Please don't tell the boss I was nervous."
  setflag p4b_aide_revived
end

# --- Sol Catacombs: tablets, sun dials, the Keeper and the altar ------------------------
script p4b_temple_scholar
  faceplayer
  if quest p4b_sol_tablets done
    say "SCHOLAR: The beast, the guardians, the ring, the Eclipse... It's all one story, and we may be living its last chapter."
  elif flag p4b_tablet1 and flag p4b_tablet2 and flag p4b_tablet3 and flag p4b_tablet4
    say "SCHOLAR: All four tablets! A beast that drank the light, two guardians who sealed it in Mt. Prism, the ring forged by Sol...\pAnd the Eclipse, older than any of us knew. Lord Umbra didn't invent his cult. He inherited it."
    if not quest p4b_sol_tablets started
      quest p4b_sol_tablets start
    end
    quest p4b_sol_tablets done
    tm solarbeam
    giveitem heatrock
    say "SCHOLAR: Solar Beam, the sacred technique of Sol. And a Heat Rock, so the harsh sunlight lasts a little longer. Thank you."
  else
    say "SCHOLAR: The Warden can sense the sun in a person's heart. Mine's partly cloudy.\pBelow the southern ruins lie the catacombs, with four tablets nobody has read in full.\pIf you go down there, would you read them all for me? Use Flash. The dead of Sol keep it dark."
    if not quest p4b_sol_tablets started
      quest p4b_sol_tablets start
    end
  end
end

script p4b_sol_tablet1
  say "An ancient tablet: \"In the first age, a beast of prisms fell from the sky and drank the light of the world. Night fell, and did not lift.\""
  setflag p4b_tablet1
  call p4b_sol_tablet_check
end

script p4b_sol_tablet2
  say "An ancient tablet: \"Two guardians answered the dark, one bright as the sun, one pale as the moon. Together they bound the beast inside the mountain of crystal.\""
  setflag p4b_tablet2
  call p4b_sol_tablet_check
end

script p4b_sol_tablet3
  say "An ancient tablet: \"The people of Sol forged a ring of light, so that the bond of trainer and Pokémon might shine as the guardians shone.\""
  setflag p4b_tablet3
  call p4b_sol_tablet_check
end

script p4b_sol_tablet4
  say "An ancient tablet: \"But some of our own grew to love the dark. They named themselves the Eclipse, and swore to wake the beast again.\""
  setflag p4b_tablet4
  call p4b_sol_tablet_check
end

script p4b_sol_tablet_check
  if flag p4b_tablet1 and flag p4b_tablet2 and flag p4b_tablet3 and flag p4b_tablet4 and not flag p4b_tablets_read
    setflag p4b_tablets_read
    if quest p4b_sol_tablets started
      quest p4b_sol_tablets next
    end
    say "That's all four tablets. The scholar in the Temple of Sol will want to hear this."
  end
end

script p4b_sol_dial_west
  setflag p4b_sol_dial1
  say "The sun dial under your feet lit up with a warm, golden glow!"
  call p4b_sol_dial_check
end

script p4b_sol_dial_east
  setflag p4b_sol_dial2
  say "The sun dial under your feet lit up with a warm, golden glow!"
  call p4b_sol_dial_check
end

script p4b_sol_dial_crypt
  setflag p4b_sol_dial3
  say "The sun dial under your feet lit up with a warm, golden glow!"
  call p4b_sol_dial_check
end

script p4b_sol_dial_check
  if flag p4b_sol_dial1 and flag p4b_sol_dial2 and flag p4b_sol_dial3 and not flag p4b_sol_open
    setflag p4b_sol_open
    shake
    say "A deep rumble... Somewhere to the north, a stone gate slid open!"
  end
end

script p4b_sol_trap
  say "The dial flared, then went dark... The floor tilted, and you slid all the way back to the stairs!"
  warp p4b_sol_catacombs 14 21 up
end

script p4b_sol_keeper
  faceplayer
  if flag p4b_beat_sol_keeper
    say "KEEPER ILIA: The guardian's ancestors kept the temple fires lit for a thousand years. Its light is yours now."
  else
    say "KEEPER ILIA: You woke the three dials. Few remember how.\pThe sun's guardians chose their partners at this altar long ago. Show me your light, and one may choose you."
    battle p4b_sol_keeper_ilia canlose
    if result win
      setflag p4b_beat_sol_keeper
      say "KEEPER ILIA: Bright as noon. Go on, the altar is waiting for you."
    else
      say "KEEPER ILIA: Your light flickers. Rest, and return. The altar will wait."
    end
  end
end

script p4b_sol_altar
  if flag p4b_beat_sol_keeper
    say "A Poké Ball rests on the altar, warm as a sunbeam. Something inside is wagging its tail..."
    givemon growlithehisui 30
    if result yes
      setflag p4b_got_sol_gift
      hide p4b_sol_altar
      say "An ancient temple guardian, Hisuian Growlithe, has chosen {player}!"
    else
      say "Your party and your boxes are full. Make some room, then return to the altar."
    end
  else
    say "A Poké Ball rests on the altar, but a strange warmth pushes your hand away. The Keeper is watching you."
  end
end

# --- Strata Gulch: Dr. Fenna's survey ---------------------------------------------------
script p4b_fenna
  faceplayer
  if quest p4b_strata_survey done
    say "FENNA: Found a fossil? The aide at Quarrytown's lab can revive Skull, Armor, Dome, Helix, Claw and Root fossils.\pTessera's rocks are full of them. I hear the crystal grotto under Mt. Prism and the hollows near Ashfall hide a few."
  elif caught klawf and caught silicobra and caught timburr
    say "FENNA: Klawf from the cliffs, Silicobra from the sand, Timburr from the old shaft. My survey's complete!\pThree layers, three ways of living. You've done real science today. Take this!"
    giveitem expertbelt
    if not quest p4b_strata_survey started
      quest p4b_strata_survey start
    end
    quest p4b_strata_survey done
    say "FENNA: An Expert Belt. It powers up super-effective hits. Every good excavator knows where to strike."
  else
    say "FENNA: I'm Dr. Fenna, the Quarry Guild's paleontologist. Every layer of this gulch is a page of history.\pI'm recording which Pokémon live in which layer today. Could you catch a Klawf, a Silicobra and a Timburr for my records?\pKlawf cling to the cliffs, Silicobra burrow in the sand here, and Timburr haul planks in Quarrytown's old shaft."
    if not quest p4b_strata_survey started
      quest p4b_strata_survey start
    end
  end
end

# --- Pylon Ridge: the lineman's hut ---------------------------------------------------
script p4b_lineman_hugo
  faceplayer
  say "HUGO: Climbed all the way up here? Sit by the stove a minute. Your Pokémon look worn out."
  heal
  if badge 4
    say "HUGO: The lines have held since you beat Garnet. Funny how the whole region feels steadier with you on the road."
  else
    say "HUGO: Those Eclipse crooks tapped our lines up here to feed their siphon. We're still finding their clamps on the pylons.\pIf you see anything odd up on the ridge, holler. Or battle it. Battling works too."
  end
end

script p4b_trade_nell
  faceplayer
  if flag p4b_traded_plusle
    say "NELL: Minun and my sister's Plusle cheer for each other all day now. Is your Plusle cheering for you?"
  else
    say "NELL: My sister has a Plusle AND a Minun. I only had a Plusle. So unfair!"
    ask "Would you trade me a Minun for my Plusle? It's very energetic."
    if result yes
      trade minun plusle 28 nick=Sparky ot=NELL
      if result 1
        setflag p4b_traded_plusle
        say "NELL: Yay! Sparky, be good for your new trainer! ...Minun, you're mine now. We're going to cheer SO much."
      else
        say "NELL: You don't have a Minun? They play in the ridge grass all day long!"
      end
    else
      say "NELL: Aww. Minun live in the tall grass outside, if you change your mind!"
    end
  end
end
