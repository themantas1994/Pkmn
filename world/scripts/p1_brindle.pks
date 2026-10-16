# Brindle City (Part 1): Gym 1, bike shop, Eevee gift, mushroom survey.

script p1_gym1_sw1
  sfx switch
  setflag p1_gym1_sw1
  say "Click! Somewhere a hedge rustled and parted."
end

script p1_gym1_sw2
  sfx switch
  setflag p1_gym1_sw2
  say "Click! The hedge to the leader's garden swung open!"
end

script p1_gym1_guide
  faceplayer
  if badge 1
    say "Congratulations, champ-to-be! The Sprout Badge lets your Pokémon use Cut outside of battle.\pAny Pokémon that can learn Cut will do. Rattata and Bidoof from Route 1 can!"
  else
    say "Yo! Future champ! This gym is a hedge maze.\pThe hedges won't budge for Cut in here. Find the floor switches instead!"
    say "Hazel uses Grass types. Fire, Flying, Bug and Poison moves work great!"
  end
end

script p1_hazel
  faceplayer
  if badge 1
    say "HAZEL: The Sprout Badge suits you! Have you tried Cut on that skinny tree on Route 2?\pIt's a shortcut back to Fernwick Town."
  else
    battle p1_leader_hazel
    say "HAZEL: Here, the Sprout Badge. Wear it with pride!"
    badge 1
    setflag p1_beat_hazel
    say "HAZEL: With it, your Pokémon can use Cut outside of battle. And take this TM, too!"
    tm bulletseed
    say "HAZEL: Bullet Seed fires a burst of seeds two to five times. Great for breaking through!\pPort Tidewater lies east, past Cobblecrest Cave. Marina, the next Gym Leader, is waiting."
    quest p1_light_shards next
  end
end

script p1_bc_gardener
  faceplayer
  if flag p1_cut_brindle
    say "You cut your way into my garden! Well, take whatever you found. I'm too old to bend down."
  elif badge 1
    say "My little garden is blocked by a skinny tree.\pYou've got Hazel's badge? Then a Pokémon that knows Cut could clear it!"
  else
    say "My little garden is sealed off by a skinny tree. There's something shiny in there...\pIf only someone had the Sprout Badge and a Pokémon that could Cut."
  end
end

script p1_bc_gym_fan
  faceplayer
  if badge 1
    say "You beat Hazel?! Her Skiddo has never lost a headbutt contest!"
  else
    say "Hazel is the nicest Gym Leader ever. Her battles are brutal though!\pStock up on Potions and Antidotes at the Mart first."
  end
end

script p1_bike_owner
  faceplayer
  if flag p1_got_bicycle
    say "How's the bike treating you? Tell everyone where you got it!"
  else
    say "Welcome to Spokes! Business is... slow. Nobody rides bikes anymore!\pI know! You look like a trainer who travels a lot."
    say "Take one of my Bicycles for free. Just ride it everywhere so people see it!"
    giveitem bicycle
    setflag p1_got_bicycle
    say "Use it from your Key Items pocket. Happy cycling!"
  end
end

script p1_eevee_lady
  faceplayer
  if flag p1_got_eevee
    say "Is my Eevee happy with you? Eevee can evolve with a Fire, Water, Thunder or Leaf Stone.\pThe stone merchant in Port Tidewater sells them!"
  else
    say "I raise Eevee! They're so special. Their genes are unstable, so they evolve in many ways."
    say "One of my babies is ready for adventure. See the Poké Ball on the bookshelf? Go on!"
  end
end

script p1_eevee_ball
  if flag p1_got_eevee
    say "It's an empty Poké Ball."
  else
    cry eevee
    ask "Will you take the Eevee?"
    if result yes
      givemon eevee 10
      if result 1
        setflag p1_got_eevee
        hide eevee_ball
        say "EEVEE LADY: Please take good care of it!"
      end
    else
      say "EEVEE LADY: Oh. Well, if you change your mind, it'll be here."
    end
  end
end

script p1_mushroom_researcher
  faceplayer
  if quest p1_mushroom_survey done
    say "RESEARCHER: Thanks to your data, my paper on forest fungi is nearly done!\pDid you know Shroomish learn Spore? That move puts anything to sleep!"
  elif caught shroomish and caught paras and caught morelull
    say "RESEARCHER: Shroomish, Paras AND Morelull? Wonderful!\pYou've earned a reward. This TM is from my field kit."
    tm gigadrain
    quest p1_mushroom_survey done
    say "RESEARCHER: Giga Drain heals the user while it attacks. Mushroom magic!"
  elif quest p1_mushroom_survey started
    say "RESEARCHER: I still need data on Shroomish, Paras and Morelull.\pShroomish and Paras like daytime in Whisperwood. Morelull only glows at night!"
  else
    say "RESEARCHER: I study mushroom Pokémon. Whisperwood is full of them!"
    say "RESEARCHER: Could you catch a Shroomish, a Paras and a Morelull for my research?\pI'll pay you back with something useful."
    quest p1_mushroom_survey start
  end
end
