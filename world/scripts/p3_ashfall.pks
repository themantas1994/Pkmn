# Ashfall City, Gym 6 (Blaise), Ember Tower.

script ash_rival_battle
  emote ash_rival !
  face ash_rival right
  playerface left
  say "{rival}: There you are, {player}! I just cooled off in the hot spring.\pBlaise's Gym is next, but first... I want to see how strong you've gotten!"
  if flag starter_grass
    battle p3_rival_ashfall_typhlosion
  elif flag starter_fire
    battle p3_rival_ashfall_feraligatr
  else
    battle p3_rival_ashfall_meganium
  end
  say "{rival}: Tch. You're the real deal.\pHey... Team Eclipse has been sniffing around Crystal Cavern south of here. Watch yourself."
  setflag p3_rival_ashfall
  hide ash_rival
end

script ash_quartz_hint
  faceplayer
  if flag p3_got_teraorb
    say "My mentor Quartz says your Tera Orb shines brighter than any crystal. Lucky!"
  elif flag p3_beat_blaise
    say "You beat Blaise? Then Quartz will want to meet you!\pHer camp is deep in Crystal Cavern, south along Route 10."
  else
    say "I'm an assistant to Quartz, the crystal scholar.\pShe only receives trainers who've earned Blaise's Ember Badge."
  end
end

script ash_gym_guide
  faceplayer
  if flag p3_beat_blaise
    say "That was white-hot! You're a true firebrand, champ-to-be."
  elif not flag p3_ash_guide_water
    say "Yo, challenger! This Gym floats on lava. Step on the floor switches to cool a path of stones.\pWater, Ground and Rock moves will douse Blaise's flames. Here, stay hydrated!"
    giveitem freshwater 2
    setflag p3_ash_guide_water
  else
    say "Three switches, three cooled paths. Blaise waits on the far platform!"
  end
end

script ash_gym_s1
  sfx switch
  shake
  say "The switch clicked! Steam hisses as the lava to the west cools."
  setflag p3_ash_s1
end

script ash_gym_s2
  sfx switch
  shake
  say "Click! A row of stepping stones rises from the lava."
  setflag p3_ash_s2
end

script ash_gym_s3
  sfx switch
  shake
  say "Click! The path to the leader's platform has cooled."
  setflag p3_ash_s3
end

script ash_blaise
  faceplayer
  if flag hall_of_fame and flag p3_beat_blaise
    say "BLAISE: The Champion returns! My flames have only grown hotter."
    ask "BLAISE: Shall we light this place up again?"
    if result yes
      battle p3_leader_blaise_rematch
      if result win
        say "BLAISE: Ha! Burned to a crisp. Come back anytime, Champion!"
        if not flag p3_blaise_rematch_prize
          giveitem charizarditex
          setflag p3_blaise_rematch_prize
        end
      end
    end
  elif flag p3_beat_blaise
    say "BLAISE: Quartz is waiting in Crystal Cavern. She'll show you a power that burns from within: Terastallization!"
  else
    say "BLAISE: Welcome to the furnace! I'm Blaise, keeper of the volcano's heart.\pFire isn't just destruction. It's the spark before every dawn!\pShow me your spark!"
    battle p3_leader_blaise
    if result win
      say "BLAISE: Whoa... you doused my fire completely. That's the Ember Badge, fair and square!"
      badge 6
      setflag p3_beat_blaise
      tm overheat
      say "BLAISE: Overheat burns hot, but it saps your Special Attack. Use it to finish strong!"
      if flag unlock_zmove
        giveitem firiumz
        say "BLAISE: And a Firium Z, for the trainer with a Z-Ring."
      end
      say "BLAISE: Now go see Quartz in Crystal Cavern, south of town."
      quest p3_story next
    end
  end
end

script ash_magma_elder
  faceplayer
  if flag hall_of_fame and not flag p3_got_redorb
    say "ELDER: You are the new Champion? Then the earth itself may answer you.\pBeneath Ember Tower lies the Magma Chamber. Something ancient sleeps there, dreaming of sunlight.\pTake this. It was found where the lava first cooled."
    giveitem redorb
    setflag p3_got_redorb
    say "ELDER: The sages will let you pass now. Go gently."
  elif flag p3_got_redorb
    say "ELDER: The Red Orb hums when it is near the chamber. Can you feel it?"
  else
    say "ELDER: Mt. Ashfall rumbles like a sleeping giant. In my youth, we said a great beast of the land slept beneath Ember Tower.\pOnly a Champion could wake it, they said."
  end
end

script ash_charcoal_kid
  faceplayer
  if not flag p3_got_charcoal
    say "I made this from a burnt log! Grandpa says it powers up Fire moves. You can have it!"
    giveitem charcoal
    setflag p3_got_charcoal
  else
    say "When I grow up I'm gonna battle Blaise with a Charizard!"
  end
end

script ash_spa_attendant
  faceplayer
  say "Welcome to the hot spring inn! Would your Pokémon like a soak?"
  ask "It's free for travelers!"
  if result yes
    heal
    say "Ahh... All refreshed! Come back anytime."
  else
    say "Maybe next time. The water's always warm!"
  end
end

script ash_spa_gentleman
  faceplayer
  if not flag p3_got_lavacookie
    say "Nothing beats a soak and a Lava Cookie. Here, have one. They're baked right on the volcano rocks!"
    giveitem lavacookie 3
    setflag p3_got_lavacookie
  else
    say "Lava Cookies cure any status problem. Handy before a long cave trip!"
  end
end

script ember_sage_stairs
  faceplayer
  say "SAGE: Beyond these stairs is the roost of the rainbow bird.\pOnly one who has entered the Hall of Fame may climb. Forgive me."
end

script ember_sage_cellar
  faceplayer
  say "SAGE: The stairs below lead into the mountain's molten heart. The way is sealed to all but the Champion."
end

script ember_sage_keeper
  faceplayer
  if flag p3_hooh_done
    say "SAGE: The rainbow bird has chosen you. May your path always be lit."
  elif flag hall_of_fame
    say "SAGE: Champion! The roof glows with rainbow light. The bird has returned at last.\pThe other sages will test you on the way. Go."
  else
    say "SAGE: Ember Tower was raised so the rainbow bird would have a place to rest.\pLegend says it appears only when a true Champion visits."
  end
end
