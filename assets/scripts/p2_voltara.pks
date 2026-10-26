# Part 2: Voltara City — blackout, power plant raid, Gym 3 (Volta), Dr. Mira's Key Stone.

script p2_voltara_enter
  if not flag p2_voltara_visited
    setflag p2_voltara_visited
    quest p2_story next
  end
end

script p2_voltara_gymguard
  faceplayer
  say "The Gym's pitch black! Volta ran off to the power plant shouting about Team Eclipse.\pThe plant is in the southeast corner of the city."
end

script p2_voltara_officer
  faceplayer
  if badge 3
    say "OFFICER: The Spark Badge! Dr. Mira asked me to send badge holders to her lab first.\pIt's the big building east of the plaza."
  else
    say "OFFICER: Route 7 is closed. The blackout knocked down power lines all over the hills.\pVolta's crew will clear them once the power is back."
  end
end

script p2_voltara_east_gate
  face vc_officer left
  if badge 3
    say "OFFICER: Not so fast! Dr. Mira wants to see you at her lab before you go."
  else
    say "OFFICER: Sorry, Route 7 is closed until the power's back!"
  end
  move player left
end

script p2_voltara_plant_rival
  setflag p2_rival_at_plant
  emote player !
  say "{rival}: {player}! You heard too? Team Eclipse took over the power plant!\pThey're draining the whole city's electricity. I'm going in. You coming or what?"
  say "{rival}: I'll take the back rooms. Meet me at the control room!"
end

script p2_voltara_rival_talk
  faceplayer
  say "{rival}: What are you waiting for? Inside!"
end

script p2_voltara_oldman
  faceplayer
  say "On clear nights you can see Mt. Prism glow in seven colors from here.\pLately the glow is... dimmer. As if something is drinking the light."
end

script p2_voltara_center_tech
  faceplayer
  if gimmick mega
    say "A Key Stone! Mega Stones are hidden all over Tessera. Look for sparkles in odd places."
  else
    say "Dr. Mira studies Mega Evolution. They say Pokémon change shape when their bond with a trainer peaks!"
  end
end

script p2_voltara_tutor
  faceplayer
  say "I am a tutor of electric arts. Shall I teach one of your Pokémon a move?"
  movetutor voltara_tutor
end

script p2_voltara_lineman
  faceplayer
  if quest p2_live_wire done
    say "LINEMAN: Every line in Voltara is humming again. Thanks, kid!"
  elif flag p2_found_toolbox
    say "LINEMAN: My toolbox! You found it on Route 7? You're a lifesaver!\pHere, I found this sparkly rock up a pylon. Some Electric Pokémon go wild for it."
    giveitem manectite
    giveitem cellbattery
    quest p2_live_wire done
  else
    say "LINEMAN: During the blackout I dropped my toolbox somewhere on Route 7. Can't fix a thing without it!"
    if not quest p2_live_wire started
      quest p2_live_wire start
    end
  end
end

script p2_dept_gift
  faceplayer
  if not flag p2_dept_gift
    say "Mom bought me two Soothe Bells by accident. You can have one!\pPokémon holding it get friendlier faster."
    giveitem soothebell
    setflag p2_dept_gift
  else
    say "My Golbat turned into Crobat after we became best friends!"
  end
end

# --- Power plant -----------------------------------------------------------
script p2_plant_worker
  faceplayer
  if flag p2_power_restored
    say "WORKER: The generators are purring again. Take that Electrium Z if you haven't. It's useless to me."
  else
    say "WORKER: They locked me in the storeroom! Here, let me patch up your Pokémon first."
    heal
    say "WORKER: The control room is just west of here. Please hurry!"
  end
end

script p2_plant_captain
  face pp_captain down
  say "CAPTAIN: A kid? Team Eclipse's siphon is almost full!\pOnce the light of Tessera is ours, the endless eclipse begins. Nobody gets in the way!"
  battle p2_pp_captain
  say "CAPTAIN: Impossible! Fine, take your precious electricity. We have what we came for!"
  fadeout
  setflag p2_plant_rival_in
  hide pp_captain
  fadein
  face pp_rival left
  say "{rival}: The back rooms are clear! Huh, did that Eclipse guy run off?\pWhatever. Hey, {player}... after all that, I'm totally fired up. Battle me!"
  if flag starter_grass
    battle p2_rival_plant_quilava canlose
  elif flag starter_fire
    battle p2_rival_plant_croconaw canlose
  else
    battle p2_rival_plant_bayleef canlose
  end
  if result win
    say "{rival}: Argh! You've gotten way stronger. I'm not done though!"
  else
    say "{rival}: Ha! Don't feel bad. I'm just that good."
  end
  heal
  say "{rival}: They said the light was 'what they came for'... Mt. Prism, maybe? I'm going to find out. See ya!"
  setflag p2_power_restored
  quest p2_story next
  fadeout
  hide pp_rival
  flash
  fadein
  say "With a deep hum, power flowed back into Voltara City!"
end

script p2_plant_rival_talk
  faceplayer
  say "{rival}: Nice work out there."
end

# --- Gym 3 ---------------------------------------------------------------------
script p2_vgym_guide
  faceplayer
  if badge 3
    say "Shocking performance, champ-to-be!"
  else
    say "Yo! Volta's barriers carry thousands of volts. Step on the floor switches to cut power to each gate.\pGround-types laugh at Electric moves. Just saying!"
  end
end

script p2_vgym_sw1
  setflag p2_vg_1
  say "Click! The first barrier powered down."
end

script p2_vgym_sw2
  setflag p2_vg_2
  say "Click! The second barrier powered down."
end

script p2_vgym_sw3
  setflag p2_vg_3
  say "Click! The last barrier powered down."
end

script p2_volta
  faceplayer
  if badge 3
    say "VOLTA: Thanks to you, the city is lit up like a stage again! Go see Dr. Mira, superstar!"
  else
    say "VOLTA: Ladies and gentlemen, the trainer who brought the lights back on!\pI'm Volta, Voltara's Gym Leader and resident showman. Now let's give the crowd a SHOCK!"
    battle p2_leader_volta
    if result win
      say "VOLTA: What a finale! You've earned this."
      badge 3
      setflag p2_badge3
      say "VOLTA: The Spark Badge lets you use Strength outside of battle. Push those boulders!"
      tm voltswitch
      say "VOLTA: That TM is Volt Switch. Hit hard, then switch out. Keep the audience guessing!"
      quest p2_story next
    end
  end
end

# --- Dr. Mira ---------------------------------------------------------------
script p2_mira
  faceplayer
  if flag p2_got_keystone
    say "DR. MIRA: Mega Evolution needs a Key Stone, the matching Mega Stone, and a deep bond.\pMega Stones are hidden around Tessera. Search everywhere!"
  elif badge 3
    say "DR. MIRA: You're the one who saved the plant! And you beat Volta too.\pI study Mega Evolution. I think you're ready for this."
    giveitem keystone
    unlock mega
    setflag p2_got_keystone
    quest p2_story next
    say "DR. MIRA: That's a Key Stone. With it, a Pokémon holding its Mega Stone can Mega Evolve once per battle.\pTake the Poké Ball on the table too. That one's been waiting for a partner like you."
  else
    say "DR. MIRA: Hello! I'm Dr. Mira. My research needs power, and right now there is none.\pIf Team Eclipse is behind this, someone has to stop them..."
  end
end

script p2_mira_aide
  faceplayer
  say "AIDE: Mega Evolution was first observed in Kalos. Dr. Mira thinks Mt. Prism's light is connected to it somehow."
end

script p2_mira_ball
  if not flag p2_got_keystone
    say "It's a Poké Ball belonging to Dr. Mira."
  else
    cry flaaffy
    ask "DR. MIRA: That's Flaaffy, with an Ampharosite. Once it evolves into Ampharos, it can Mega Evolve! Take it?"
    if result yes
      givemon flaaffy 25 moves=thundershock,cottonspore,powergem,thunderwave
      if result 1
        giveitem ampharosite
        setflag p2_got_mira_gift
        hide mira_ball
      end
    end
  end
end
