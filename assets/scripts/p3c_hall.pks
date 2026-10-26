# Part 3C: Champions' Hall (Gym Leader rematches) and the Rematch Chamber (Elite Four + Champion Celeste).
# Leader rematches are repeatable; the first win against each gives a TM and a Bottle Cap (flags p3c_beat_<leader>).

script p3c_hall_steward
  faceplayer
  say "STEWARD: Welcome to the Champions' Hall. Tessera's eight Gym Leaders train here between seasons, and all of them want a rematch.\pDefeat all eight and the Rematch Chamber upstairs opens. The Elite Four and Champion Celeste await, with no rest between battles.\pEvery battle here can be repeated as often as you like."
  if quest p3c_isles step < 1
    quest p3c_isles 1
  end
end

script p3c_hall_board
  say "CHAMPIONS' HALL RECORD\pHazel, Marina, Volta, Garnet, Elowen, Blaise, Isolde, Ryuga.\pBeneath the list: \"Those who beat all eight may climb the stairs.\""
end

script p3c_hall_gate
  if flag p3c_beat_hazel and flag p3c_beat_marina and flag p3c_beat_volta and flag p3c_beat_garnet and flag p3c_beat_elowen and flag p3c_beat_blaise and flag p3c_beat_isolde and flag p3c_beat_ryuga
    setflag p3c_league_open
    say "The stairs hum with power. The Rematch Chamber is open!"
  else
    say "A voice from above: \"Only those who have bested all eight Leaders in this hall may enter.\""
    move player down
  end
end

# Called after every leader rematch win. Advances the hall quest once all eight are beaten.
script p3c_hall_check
  if flag p3c_beat_hazel and flag p3c_beat_marina and flag p3c_beat_volta and flag p3c_beat_garnet and flag p3c_beat_elowen and flag p3c_beat_blaise and flag p3c_beat_isolde and flag p3c_beat_ryuga
    if not flag p3c_all_leaders
      setflag p3c_all_leaders
      say "All eight Leaders have fallen! The Rematch Chamber upstairs is open."
      if quest p3c_isles step < 2
        quest p3c_isles 2
      end
    end
  end
end

script p3c_talk_hazel
  faceplayer
  say "HAZEL: Look at you! The little sprout from Brindle, all grown up.\pMy garden's grown too. Want to see?"
  ask "Battle Hazel?"
  if result yes
    battle p3c_rm_hazel canlose
    if result win
      if not flag p3c_beat_hazel
        setflag p3c_beat_hazel
        say "HAZEL: Take this. It's the strongest Grass move I know."
        tm leafstorm
        giveitem bottlecap
        call p3c_hall_check
      else
        say "HAZEL: Still blooming. Wonderful!"
      end
    else
      say "HAZEL: Even oaks get pruned now and then. Come back soon!"
    end
    heal
  else
    say "HAZEL: Anytime, dear."
  end
end

script p3c_talk_marina
  faceplayer
  say "MARINA: Ahoy, Champion! The tide's high and so am I. Ready to get soaked?"
  ask "Battle Marina?"
  if result yes
    battle p3c_rm_marina canlose
    if result win
      if not flag p3c_beat_marina
        setflag p3c_beat_marina
        say "MARINA: A tidal wave of a trainer! Here, a little splash for the road."
        tm hydropump
        giveitem bottlecap
        call p3c_hall_check
      else
        say "MARINA: You never ebb, do you?"
      end
    else
      say "MARINA: The tide turned my way this time!"
    end
    heal
  else
    say "MARINA: The sea's patient. So am I."
  end
end

script p3c_talk_volta
  faceplayer
  say "VOLTA: The superstar returns! Lights, camera... rematch!"
  ask "Battle Volta?"
  if result yes
    battle p3c_rm_volta canlose
    if result win
      if not flag p3c_beat_volta
        setflag p3c_beat_volta
        say "VOLTA: What a show! Take this as your encore prize."
        tm thunder
        giveitem bottlecap
        call p3c_hall_check
      else
        say "VOLTA: Another sold-out performance. For you."
      end
    else
      say "VOLTA: The crowd goes wild! For me, for once!"
    end
    heal
  else
    say "VOLTA: The stage is always yours, superstar."
  end
end

script p3c_talk_garnet
  faceplayer
  say "GARNET: The Champion, eh? Let's see if you're still bedrock."
  ask "Battle Garnet?"
  if result yes
    battle p3c_rm_garnet canlose
    if result win
      if not flag p3c_beat_garnet
        setflag p3c_beat_garnet
        say "GARNET: Still solid. Take this. Hits like a landslide."
        tm stoneedge
        giveitem bottlecap
        call p3c_hall_check
      else
        say "GARNET: Granite, through and through."
      end
    else
      say "GARNET: Ha! A crack in the bedrock!"
    end
    heal
  else
    say "GARNET: I'll be here, polishing my rocks."
  end
end

script p3c_talk_elowen
  faceplayer
  say "ELOWEN: The little star returns. Let the mist reveal your heart once more."
  ask "Battle Elowen?"
  if result yes
    battle p3c_rm_elowen canlose
    if result win
      if not flag p3c_beat_elowen
        setflag p3c_beat_elowen
        say "ELOWEN: Your light outshines the moon. Take this gift from the grove."
        tm moonblast
        giveitem bottlecap
        call p3c_hall_check
      else
        say "ELOWEN: Radiant, as always."
      end
    else
      say "ELOWEN: The mist wins tonight. Shine again tomorrow."
    end
    heal
  else
    say "ELOWEN: The mist will wait for you."
  end
end

script p3c_talk_blaise
  faceplayer
  say "BLAISE: Champion! My forge is white-hot. Ready to be tempered again?"
  ask "Battle Blaise?"
  if result yes
    battle p3c_rm_blaise canlose
    if result win
      if not flag p3c_beat_blaise
        setflag p3c_beat_blaise
        say "BLAISE: Masterwork! Here, the hottest move I know."
        tm fireblast
        giveitem bottlecap
        call p3c_hall_check
      else
        say "BLAISE: Tempered steel once again!"
      end
    else
      say "BLAISE: Back to the anvil with you!"
    end
    heal
  else
    say "BLAISE: The fire never goes out. Come back anytime."
  end
end

script p3c_talk_isolde
  faceplayer
  say "ISOLDE: The Champion. The ice remembers you. Shall we see if it has learned anything?"
  ask "Battle Isolde?"
  if result yes
    battle p3c_rm_isolde canlose
    if result win
      if not flag p3c_beat_isolde
        setflag p3c_beat_isolde
        say "ISOLDE: A thaw in midwinter... Take this. May it freeze your foes as you warmed me."
        tm blizzard
        giveitem bottlecap
        call p3c_hall_check
      else
        say "ISOLDE: The ice cracks again. Well done."
      end
    else
      say "ISOLDE: The cold claims another. Return when you've warmed up."
    end
    heal
  else
    say "ISOLDE: Winter waits."
  end
end

script p3c_talk_ryuga
  faceplayer
  say "RYUGA: Dragons wake hungry, Champion. Shall we feed them?"
  ask "Battle Ryuga?"
  if result yes
    battle p3c_rm_ryuga canlose
    if result win
      if not flag p3c_beat_ryuga
        setflag p3c_beat_ryuga
        say "RYUGA: The dragon bows. Take its fury with you."
        tm dracometeor
        giveitem bottlecap
        call p3c_hall_check
      else
        say "RYUGA: Again the dragon bows."
      end
    else
      say "RYUGA: The dragon feasts tonight. Return stronger."
    end
    heal
  else
    say "RYUGA: The dragons will keep."
  end
end

# ---------------------------------------------------------------- Rematch Chamber
script p3c_league_enter
  clearflag p3c_e4v_morrow
  clearflag p3c_e4v_ferrum
  clearflag p3c_e4v_sibyl
  clearflag p3c_e4v_nox
end

script p3c_league_aide
  faceplayer
  say "AIDE: Welcome to the Rematch Chamber. Defeat Morrow, Ferrum, Sibyl and Nox in any order, then Champion Celeste will face you.\pThere's no healing between battles. If you leave the chamber, the challenge starts over.\pEvery gimmick is allowed here. Good luck!"
end

script p3c_e4_lost
  say "Your challenge is over. You were carried back to the hall to recover."
  clearflag p3c_e4v_morrow
  clearflag p3c_e4v_ferrum
  clearflag p3c_e4v_sibyl
  clearflag p3c_e4v_nox
  heal
  warp champions_hall 7 2 down
end

script p3c_talk_morrow
  faceplayer
  if flag p3c_e4v_morrow
    say "MORROW: The spirits are resting. Go on, the others await."
    return
  end
  say "MORROW: Welcome back to the shadows, Champion."
  battle p3c_rm_morrow canlose
  if result win
    setflag p3c_e4v_morrow
    say "MORROW: The veil parts. Proceed."
    call p3c_e4_check
  else
    call p3c_e4_lost
  end
end

script p3c_talk_ferrum
  faceplayer
  if flag p3c_e4v_ferrum
    say "FERRUM: My armor needs polishing. Go on."
    return
  end
  say "FERRUM: Steel hardens with every strike. Strike, Champion!"
  battle p3c_rm_ferrum canlose
  if result win
    setflag p3c_e4v_ferrum
    say "FERRUM: Unbreakable. Proceed."
    call p3c_e4_check
  else
    call p3c_e4_lost
  end
end

script p3c_talk_sibyl
  faceplayer
  if flag p3c_e4v_sibyl
    say "SIBYL: I see the Champion's dais in your future. Go."
    return
  end
  say "SIBYL: I foresaw you coming. Let's see if I foresaw the rest."
  battle p3c_rm_sibyl canlose
  if result win
    setflag p3c_e4v_sibyl
    say "SIBYL: As foretold. Proceed."
    call p3c_e4_check
  else
    call p3c_e4_lost
  end
end

script p3c_talk_nox
  faceplayer
  if flag p3c_e4v_nox
    say "NOX: The night has ended for me. Go."
    return
  end
  say "NOX: Night falls again, Champion."
  battle p3c_rm_nox canlose
  if result win
    setflag p3c_e4v_nox
    say "NOX: And dawn breaks. Proceed."
    call p3c_e4_check
  else
    call p3c_e4_lost
  end
end

script p3c_e4_check
  if flag p3c_e4v_morrow and flag p3c_e4v_ferrum and flag p3c_e4v_sibyl and flag p3c_e4v_nox
    say "All four have fallen. Champion Celeste is waiting at the top of the chamber."
  end
end

script p3c_talk_celeste
  faceplayer
  if not flag p3c_e4v_morrow or not flag p3c_e4v_ferrum or not flag p3c_e4v_sibyl or not flag p3c_e4v_nox
    say "CELESTE: Ah, Champion. Defeat my Elite Four first. All four, without rest. Then we dance."
    return
  end
  say "CELESTE: You came back. I hoped you would.\pI've trained every day since you took my crown. Today, I take it back!"
  battle p3c_rm_celeste canlose
  if result win
    say "CELESTE: Radiant. Truly. The crown is yours, now and always."
    if not flag p3c_champion_rematch
      setflag p3c_champion_rematch
      say "CELESTE: Take this. It is the only one I ever owned. Use it on a Pokémon worthy of you.\pAnd one more thing: a ranger on Route 17 guards a sealed grotto. I've told her you may pass."
      giveitem masterball
      giveitem goldbottlecap
      if quest p3c_isles step < 3
        quest p3c_isles 3
      end
    end
    clearflag p3c_e4v_morrow
    clearflag p3c_e4v_ferrum
    clearflag p3c_e4v_sibyl
    clearflag p3c_e4v_nox
    heal
    say "CELESTE: Your team is restored. Come dance again whenever you like."
  else
    say "CELESTE: The crown returns to me... for today."
    call p3c_e4_lost
  end
end
