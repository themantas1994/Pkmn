# Victory Road, Prism Summit (Team Eclipse finale), Pokémon League, Hall of Fame.

script vg_guard
  faceplayer
  if badges >= 8
    say "OFFICER: All eight badges! Victory Road is a maze of caves and waterfalls. Good luck."
  else
    say "OFFICER: Only trainers with all eight Tessera badges may enter Victory Road."
  end
end

script vg_badge_check
  if badges >= 8
    say "OFFICER: Let me see your badges... Eight! You may pass."
    setflag p3_vr_pass
  else
    say "OFFICER: Hold it! You need all eight badges to go any farther."
    move player down
  end
end

script vr2_grunt_block
  faceplayer
  say "GRUNT: Boss Umbra's orders! Nobody goes deeper while the ritual's on.\pIf you've got business, take it up the path to the summit. Heh, if you dare!"
end

# ------------------------------------------------------------------ Prism Summit
script sum_warden_heal
  faceplayer
  if flag p3_umbra_defeated
    say "WARDEN: The summit's light is calm again. Thank you, young one."
  else
    say "WARDEN: I am the summit's warden. Team Eclipse forced me aside...\pLet me tend to your Pokémon. You must stop them!"
    heal
    say "WARDEN: Your team is restored. Go!"
  end
end

script sum_vesper
  faceplayer
  say "VESPER: Mmm, the child who keeps meddling. Corvin told me all about you.\pThe light here will soon be ours. Let me show you a nightmare first!"
  battle p3_admin_vesper
  say "VESPER: Such brilliance... it hurts to look at. Fine, go. Umbra will snuff you out."
  setflag p3_vesper_defeated
  hide vesper
end

script sum_corvin
  faceplayer
  say "CORVIN: You again! Mt. Prism wasn't enough, huh? This time, there's no tunnel to run into."
  battle p3_admin_corvin
  say "CORVIN: Tch... twice now. The boss won't be so easy."
  setflag p3_corvin_defeated
  hide corvin
end

script sum_necrozma_hint
  say "A creature of black crystal lies dormant, tangled in Team Eclipse's cables. Its light is fading..."
end

script sum_umbra
  faceplayer
  say "UMBRA: ..."
end

script sum_umbra_event
  emote umbra !
  face umbra down
  say "UMBRA: So. The light-bringer arrives.\pDo you know what this is? The Prism Pokémon. It devours light, and it has been starving for a thousand years."
  say "UMBRA: Every war, every drought, every cruel noon sun... A world without harsh light would be a gentler one. An endless, soft twilight.\pI will give Tessera that mercy. You will not stop me!"
  battle p3_boss_umbra
  say "UMBRA: Defeated... by a child's light. Then perhaps... I was wrong about the dark."
  shake
  cry necrozma
  say "The machine sparks and explodes! The black crystal creature tears free, blazing with stolen light!"
  say "UMBRA: No... it's awake! Run!"
  setflag p3_umbra_defeated
  hide umbra
  hide necrozma
  wildbattle necrozma 60 norun
  if result caught
    setflag p3_necrozma_caught
    say "You caught the Prism Pokémon! Necrozma's light settles, calm and steady."
  else
    setflag p3_necrozma_defeated
    say "Necrozma shattered into countless motes of light and vanished into the sky.\pPerhaps one day it will gather itself here again..."
  end
  say "Team Eclipse's grunts scattered down the mountain. The summit is quiet."
  quest p3_story next
end

# ------------------------------------------------------------------ Victory Road 3F: final rival
script vr3_rival_talk
  faceplayer
  say "{rival}: ..."
end

script vr3_rival_battle
  emote vr3_rival !
  face vr3_rival down
  say "{rival}: {player}! I heard what you did on the summit. Of course it was you.\pYou know what? I'm glad. Because now, beating you will actually mean something!"
  say "{rival}: No holding back. This is the last battle before the League. Let's go!"
  if flag starter_grass
    battle p3_rival_vr_typhlosion
  elif flag starter_fire
    battle p3_rival_vr_feraligatr
  else
    battle p3_rival_vr_meganium
  end
  say "{rival}: ...Yeah. You're stronger. For now.\pGo win it all, {player}. If anyone's going to beat the Champion before me, it should be you."
  setflag p3_rival_vr
  hide vr3_rival
end

# ------------------------------------------------------------------ League
# Design: classic gauntlet. The lobby nurse and shop are the last chance to heal; once the player
# confirms at the gate, the four Elite Four rooms and the Champion are fought back to back with
# items only. Each room's exit stairs appear when its member is beaten; there is no way back.
# The gate clears the room flags, so a lost run must restart from Morrow.

script league_attendant_talk
  faceplayer
  say "ATTENDANT: Beyond this door: the Elite Four, then the Champion, one after another.\pThere is no healing past this point. Prepare well!"
end

script league_gate
  face attendant left
  ask "ATTENDANT: Are you ready to challenge the Pokémon League?"
  if result yes
    clearflag p3_e4_1
    clearflag p3_e4_2
    clearflag p3_e4_3
    clearflag p3_e4_4
    clearflag p3_champ_beaten
    say "ATTENDANT: Then go forth. The stairs ahead lead to the first room."
    move player up
  else
    say "ATTENDANT: Take your time. The nurse and the shop are here to help."
    move player down
  end
end

script league_morrow
  faceplayer
  if flag p3_e4_1
    say "MORROW: The way forward is open. Don't look back... something might be following."
  else
    say "MORROW: Welcome, challenger. I am Morrow of the Elite Four.\pMy ghosts remember every trainer who stood where you stand. Most of them... never left."
    if flag hall_of_fame
      battle p3_e4_morrow_rematch
    else
      battle p3_e4_morrow
    end
    setflag p3_e4_1
    say "MORROW: Heh heh... you have a living spirit. The stairs are open. Go."
  end
end

script league_ferrum
  faceplayer
  if flag p3_e4_2
    say "FERRUM: Forward, challenger. Steel bends for no one, but it yields to you."
  else
    say "FERRUM: I am Ferrum. Steel is patience, hammered into shape over years.\pLet's see if your team can dent mine!"
    if flag hall_of_fame
      battle p3_e4_ferrum_rematch
    else
      battle p3_e4_ferrum
    end
    setflag p3_e4_2
    say "FERRUM: Ha! A clean strike. The next room awaits."
  end
end

script league_sibyl
  faceplayer
  if flag p3_e4_3
    say "SIBYL: I already know how your next battle ends. I won't spoil it."
  else
    say "SIBYL: I am Sibyl. I foresaw your arrival the day you chose your first Pokémon.\pBut visions are only possibilities. Let's see which one is true."
    if flag hall_of_fame
      battle p3_e4_sibyl_rematch
    else
      battle p3_e4_sibyl
    end
    setflag p3_e4_3
    say "SIBYL: So that was the true future. Go on, the last of us waits."
  end
end

script league_nox
  faceplayer
  if flag p3_e4_4
    say "NOX: Go. The Champion has been waiting for someone like you."
  else
    say "NOX: Nox. Last of the Elite Four. I fight in the dark because that's where the truth hides.\pNo tricks, no mercy. Show me what's real!"
    if flag hall_of_fame
      battle p3_e4_nox_rematch
    else
      battle p3_e4_nox
    end
    setflag p3_e4_4
    say "NOX: ...Real enough. The Champion's room is up the stairs."
  end
end

script league_celeste
  faceplayer
  if flag p3_champ_beaten
    say "CELESTE: Come, the Hall of Fame is just beyond. Your Pokémon deserve to be remembered."
  else
    say "CELESTE: Welcome. I'm Celeste, Champion of Tessera.\pI watched you on Prism Summit. You stood in the brightest light and didn't flinch."
    say "CELESTE: Mega Evolution, Z-Power, Terastal... I have mastered them all. Now give me everything, and I will answer with everything!"
    if flag hall_of_fame
      battle p3_champion_celeste_rematch
    else
      battle p3_champion_celeste
    end
    setflag p3_champ_beaten
    say "CELESTE: ...Wonderful. The light of a new Champion. Follow me, {player}. The Hall of Fame awaits."
  end
end

script league_hall_of_fame
  say "CELESTE: This is the Hall of Fame, where the Pokémon League honors its Champions and their partners."
  say "PROF. LINDEN: {player}! I came as soon as I heard. Look at you... and look at your Pokémon. You're a family."
  halloffame
  if not flag hall_of_fame
    setflag hall_of_fame
    if not flag p3_necrozma_caught
      setflag p3_necrozma_returns
    end
    quest p3_story done
    credits
    warp player_house_2f 3 4 down
    say "The adventure continues! New paths have opened across Tessera.\pRumor has it a ferry now sails from Port Tidewater to the Coral Isles..."
    quest p3_postgame start
  else
    say "CELESTE: Your name shines a little brighter in the Hall today."
    warp prism_plateau 11 9 down
  end
end
