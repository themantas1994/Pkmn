# Part 2: Route 7, Route 8, Quarrytown, Ruins of Sol.

script p2_r7_toolbox
  say "It's a battered toolbox stenciled \"VOLTARA POWER & LIGHT\"."
  setflag p2_found_toolbox
  hide r7_toolbox
  if quest p2_live_wire started
    quest p2_live_wire next
  end
  say "{player} picked up the toolbox. Better return it to its owner in Voltara City."
end

script p2_r8_pickaxe
  say "A sturdy pickaxe with \"LUCKY\" scratched into the handle."
  setflag p2_found_pickaxe
  hide r8_pickaxe
  if quest p2_fossil_dig started
    quest p2_fossil_dig next
  end
  say "{player} picked up the pickaxe. Someone in Quarrytown must be missing it."
end

# --- Quarrytown ----------------------------------------------------------------
script p2_quarry_acolyte
  faceplayer
  if badge 4
    say "ACOLYTE: The Stone Badge! Then Warden Talo will want to meet you.\pThe Temple of Sol lies east of town. Mt. Prism is no place to go without his blessing."
  else
    say "ACOLYTE: Mt. Prism is sacred, and lately dangerous. Strange people in black came through here.\pBy Warden Talo's order, only trainers he has blessed may enter."
  end
end

script p2_quarry_relearner
  faceplayer
  say "I'm an old miner, but I never forget a move! I can help your Pokémon remember theirs."
  moverelearner
end

script p2_quarry_trader
  faceplayer
  if flag p2_quarry_traded
    say "Drilbur and I dig tunnels all day. Thanks for trading!"
  else
    ask "I want a Drilbur so bad! I'll trade you my Onix for one. Deal?"
    if result yes
      trade drilbur onix 30 nick=Rocky item=metalcoat ot=DIGGER
      if result 1
        setflag p2_quarry_traded
        say "Yahoo! Take good care of Rocky!"
      else
        say "You don't have a Drilbur? They live on Route 8!"
      end
    else
      say "Aww. Drilbur live on Route 8, if you change your mind!"
    end
  end
end

script p2_quarry_foreman
  faceplayer
  if quest p2_fossil_dig done
    say "FOREMAN: How's that fossil Pokémon of yours? Older than this whole town, I bet!"
  elif flag p2_fossil_given
    say "FOREMAN: Take that fossil to the lab north of the square. They'll wake it right up."
  elif flag p2_found_pickaxe
    say "FOREMAN: My lucky pickaxe! I can't dig a single stone without it.\pAs thanks, pick one of these fossils from the last dig."
    choice "Which fossil?" "Jaw Fossil" "Sail Fossil"
    if result 0
      giveitem jawfossil
    else
      giveitem sailfossil
    end
    setflag p2_fossil_given
    if not quest p2_fossil_dig started
      quest p2_fossil_dig start
      quest p2_fossil_dig next
    end
    quest p2_fossil_dig next
  else
    say "FOREMAN: Lost my lucky pickaxe on Route 8, somewhere in the north canyon rocks.\pBring it back and I'll make it worth your while!"
    if not quest p2_fossil_dig started
      quest p2_fossil_dig start
    end
  end
end

script p2_fossil_reviver
  faceplayer
  if item jawfossil
    say "REVIVER: A Jaw Fossil! Give me a moment..."
    takeitem jawfossil
    givemon tyrunt 30
    call p2_fossil_done
  elif item sailfossil
    say "REVIVER: A Sail Fossil! Give me a moment..."
    takeitem sailfossil
    givemon amaura 30
    call p2_fossil_done
  elif item oldamber
    say "REVIVER: Old Amber! There's DNA trapped inside. Give me a moment..."
    takeitem oldamber
    givemon aerodactyl 30
    call p2_fossil_done
  else
    say "REVIVER: Bring me fossils and I'll bring them back to life! Jaw, Sail, Old Amber... anything!"
  end
end

script p2_fossil_done
  say "REVIVER: Our machine worked perfectly. Be kind to it, it's been asleep for millions of years!"
  if quest p2_fossil_dig started and not quest p2_fossil_dig done
    quest p2_fossil_dig done
  end
end

# --- Quarrytown Gym ---------------------------------------------------------------
script p2_qgym_guide
  faceplayer
  if badge 4
    say "You cracked the Stone Badge! Rock solid!"
  else
    say "Yo! The mine carts block the gaps in the ridges. Step on the track switches to shunt them aside.\pOne switch flips back and forth. Choose your track wisely!\pRock-types hate Water, Grass, Fighting, Ground and Steel."
  end
end

script p2_qgym_sw_a
  setflag p2_qg_a
  say "Clunk! A mine cart rolled down the track."
end

script p2_qgym_sw_b
  if flag p2_qg_b
    clearflag p2_qg_b
    say "Clunk! The track switched to the right."
  else
    setflag p2_qg_b
    say "Clunk! The track switched to the left."
  end
end

script p2_qgym_sw_c
  setflag p2_qg_c
  say "Clunk! The last mine cart rolled away."
end

script p2_garnet
  faceplayer
  if badge 4
    say "GARNET: Warden Talo at the Ruins of Sol will want to see a trainer like you. Head east!"
  else
    say "GARNET: So you made it through my mine. I'm Garnet, foreman of this quarry and its Gym Leader.\pMy Pokémon are carved from the bones of the earth. And one of them has a little something extra..."
    battle p2_leader_garnet
    if result win
      say "GARNET: You've got a backbone of granite. Take the Stone Badge!"
      badge 4
      setflag p2_badge4
      say "GARNET: With it, your Pokémon can Surf outside of battle. Rivers and lakes are yours now."
      tm rockslide
      say "GARNET: Rock Slide! Crushes the foe and might make 'em flinch."
      quest p2_story next
    end
  end
end

# --- Ruins of Sol ---------------------------------------------------------------
script p2_talo
  faceplayer
  if flag p2_got_zring
    say "WARDEN TALO: The Z-Ring answers to bonds, not strength. Strike when your heart is in sync with your Pokémon.\pNow go. Mt. Prism is crying out."
  elif badge 4
    say "WARDEN TALO: I felt you coming the way one feels the dawn. You bear the Stone Badge.\pTeam Eclipse would swallow the sun. To stand against them, take this."
    giveitem zring
    unlock zmove
    giveitem normaliumz
    giveitem rockiumz
    setflag p2_got_zring
    quest p2_story next
    say "WARDEN TALO: With the Z-Ring and a Z-Crystal, a Pokémon can unleash a Z-Move once per battle.\pMy acolyte at Quarrytown will let you into Mt. Prism. Hurry!"
  else
    say "WARDEN TALO: Hmm. The sun in you is still rising. Return when Garnet of Quarrytown acknowledges you."
  end
end

script p2_sol_acolyte
  faceplayer
  if quest p2_sun_and_moon done
    say "ACOLYTE: Sun and moon, side by side in your Pokédex. Beautiful."
  elif caught solrock and caught lunatone
    say "ACOLYTE: You caught both Solrock and Lunatone? The sun and moon united!\pPlease, accept the temple's thanks."
    giveitem sunstone
    giveitem moonstone
    giveitem firiumz
    quest p2_sun_and_moon done
  else
    say "ACOLYTE: Solrock wanders these ruins by day, Lunatone by night. They never meet.\pIf you could catch them both, I'd love to see them side by side."
    if not quest p2_sun_and_moon started
      quest p2_sun_and_moon start
    end
  end
end
