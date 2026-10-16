# Expansion E1, leg 1 (start -> Gym 1): Brookside Trail, Angler's Cottage, Brookspring Grotto, Mossgrove Thicket,
# Mossgrove Glade, Root Hollow, Brindle flower shop, Pip (rematch), Fernwick trade, catch-count aide #1.

# ---------------------------------------------------------------- Hollowbrook / Brookside Trail
script p4a_ht_trail_sign
  say "BROOKSIDE TRAIL - east\nFollow the brook upstream."
end

script p4a_bt_sign
  say "BROOKSIDE TRAIL\nHollowbrook Town - west\pAnglers welcome! Please don't feed the Magikarp."
end

script p4a_bt_kid
  faceplayer
  if item oldrod
    say "You've got a rod too? Face the water and use it from your bag!\pThe brook is full of Poliwag. Lotad float by sometimes, like little lily pads."
  else
    say "I wish I had a fishing rod. The old fisherman in Hollowbrook has one he never uses.\pMaybe he'd give it to you if you asked!"
  end
end

script p4a_odd_tree
  if item squirtbottle
    say "A weird tree. It's wiggling ever so slightly..."
    ask "Water it with the Squirt Bottle?"
    if result yes
      say "{player} sprinkled water on the odd tree.\pThe tree shuddered... and leapt up!"
      cry sudowoodo
      wildbattle sudowoodo 12 norun
      if not result lose
        setflag p4a_sudowoodo_done
        hide p4a_odd_tree
        quest p4a_odd_tree done
        say "The odd tree is gone. There's a little nook behind where it stood!"
      end
    end
  else
    say "A weird tree. It doesn't look like any tree you've ever seen.\pIts branches twitch whenever the brook splashes. Does it want water?"
  end
end

script p4a_angler
  faceplayer
  if quest p4a_fishing_log done
    say "ANGLER ROSS: Caught anything big lately?\pWith a Good Rod you can hook Chewtle and Barboach right off my porch."
  elif caught magikarp and caught poliwag and caught goldeen
    say "ANGLER ROSS: Let me see that log... Magikarp, Poliwag AND Goldeen! You're a natural!"
    say "ANGLER ROSS: This Mystic Water was my lucky charm. It powers up Water moves.\pAnd take these Lure Balls. They work wonders on hooked Pokémon."
    giveitem mysticwater
    giveitem lureball 3
    quest p4a_fishing_log done
  elif quest p4a_fishing_log started
    say "ANGLER ROSS: Your log still needs a Magikarp, a Poliwag and a Goldeen.\pPoliwag bite here and in Hollowbrook's pond. Goldeen prefer the pond in Fernwick Town."
  else
    say "ANGLER ROSS: Ah, a young trainer! Do you fish? I've fished this brook for sixty years."
    if item oldrod
      say "ANGLER ROSS: An Old Rod! Splendid. Let's start you a fishing log."
    else
      say "ANGLER ROSS: No rod? The fisherman by Hollowbrook's pond has an old one gathering dust."
    end
    say "ANGLER ROSS: Catch a Magikarp, a Poliwag and a Goldeen, then show me.\pI'll make it worth your while!"
    quest p4a_fishing_log start
  end
end

script p4a_bt_granny
  faceplayer
  if visited p4a_brook_grotto
    say "You found the little spring? Then you've seen where our brook is born.\pIt starts as snow on Mt. Prism and travels underground for years before it reaches us."
  else
    say "When I was a girl, I followed the brook upstream to a little cave behind the trees.\pIt's northeast of here, past the tall grass. The water there sparkles like crystal."
  end
end

script p4a_bg_spring
  say "A clear spring bubbles up between the rocks.\pThe water is icy cold and tastes faintly of minerals. It must come from deep under Mt. Prism."
end

# ---------------------------------------------------------------- Mossgrove Thicket / Glade / Root Hollow
script p4a_mg_sign
  say "MOSSGROVE THICKET\pRangers' notice: the ledges here only go one way. Mind your step!"
end

script p4a_mg_gate_sign
  if flag p4a_mg_gate
    say "The bushes have parted. A soft green light shines from the glade beyond."
  else
    say "An old carving, half hidden by moss:\p\"Wake the two stones of the grove, and the green door shall open.\""
  end
end

script p4a_mg_stone_a
  sfx switch
  setflag p4a_mg_stone_a
  say "The mossy stone under your feet began to glow a soft green!"
  call p4a_mg_check_gate
end

script p4a_mg_stone_b
  sfx switch
  setflag p4a_mg_stone_b
  say "The mossy stone under your feet began to glow a soft green!"
  call p4a_mg_check_gate
end

script p4a_mg_check_gate
  if flag p4a_mg_stone_a and flag p4a_mg_stone_b
    setflag p4a_mg_gate
    shake
    say "Somewhere to the east, branches creaked and rustled apart!"
  else
    say "Nothing else happened. Maybe there's another stone somewhere..."
  end
end

script p4a_gl_mossrock
  say "A large rock blanketed in thick, soft moss.\pIt feels strangely warm. Grass Pokémon seem to gather around it."
end

script p4a_rh_carving
  say "Ancient marks are carved into the roots and stone.\pThey look like the letters on the murals of the Lumen Ruins... Two eyes, and a door between them."
end

script p4a_rh_switch1
  sfx switch
  setflag p4a_rh_wall1
  shake
  say "The stone sank with a heavy thunk. Somewhere, a wall of roots slid aside!"
end

script p4a_rh_switch2
  sfx switch
  setflag p4a_rh_wall2
  shake
  say "Another thunk! The roots to the south creaked open."
end

# ---------------------------------------------------------------- Brindle City: flower shop
script p4a_bc_florist_sign
  say "PETAL & STEM FLOWER SHOP\nFresh from Mossgrove!"
end

script p4a_florist
  faceplayer
  if quest p4a_odd_tree done
    say "FLORIST: That odd tree turned out to be a Pokémon? So that's why it always wiggled when I watered it!"
  elif flag p4a_florist_thanked
    say "FLORIST: Keep that Squirt Bottle handy! The odd tree is on the Brookside Trail, east of Hollowbrook.\pGive it a good sprinkle and see what happens."
  elif item squirtbottle
    say "FLORIST: My Squirt Bottle! You found it in Mossgrove? I must have dropped it while gathering moss."
    say "FLORIST: You know what? Keep it. I bought a new one. And take this as thanks!"
    tm magicalleaf
    setflag p4a_florist_thanked
    quest p4a_odd_tree 1
    say "FLORIST: Oh, and speaking of watering... There's an odd tree on the Brookside Trail, east of Hollowbrook.\pIt wiggles when nobody's looking. I've always wondered what a sprinkle would do!"
  else
    say "FLORIST: Welcome to Petal & Stem! ...Sorry, I'm all flustered.\pI lost my Squirt Bottle gathering moss in Mossgrove Thicket, past the east path in Whisperwood."
    if not quest p4a_odd_tree started
      quest p4a_odd_tree start
    end
    say "FLORIST: If you find it, please bring it back. I'll make it worth your while!"
  end
end

script p4a_florist_kid
  faceplayer
  if badge 1
    say "You beat Hazel? She buys all her fertilizer here! Her Pokémon grow huge."
  else
    say "Mom's flowers come from Mossgrove Thicket. You need to light two old stones to reach the best spot.\pRangers say one stone is way up in the northeast corner."
  end
end

# ---------------------------------------------------------------- Route 1: Pip (phone-style rematch)
script p4a_pip
  faceplayer
  if not trainer p4a_pip_1
    say "PIP: You just got your first Pokémon too, right? Let's battle! Winner gets bragging rights!"
    battle p4a_pip_1
    say "PIP: Wow, you're good! I'm gonna train right here on Route 1 every day.\pCome back after you've earned some badges. I'll be ready for a rematch!"
  elif badges >= 6 and not trainer p4a_pip_4
    say "PIP: {player}! Six badges? Then it's time for our ultimate rematch!"
    battle p4a_pip_4
    say "PIP: Haha... you really are the best. Here, take this. I found it on my morning run."
    giveitem rarecandy
  elif badges >= 4 and not trainer p4a_pip_3
    say "PIP: Hey, {player}! I heard you've got four badges now. Rematch time!"
    battle p4a_pip_3
    say "PIP: Argh, so close! Take these. I bought them for myself, but you clearly need them more."
    giveitem hyperpotion 2
  elif badges >= 2 and not trainer p4a_pip_2
    say "PIP: {player}! You're back! My team evolved since last time. Let's go!"
    battle p4a_pip_2
    say "PIP: You're still better than me... Here, have these. Fair's fair!"
    giveitem superpotion 3
  elif trainer p4a_pip_4
    say "PIP: I'm going to be a Gym Leader someday. The Route 1 Gym! Bug-free, all Normal types."
  else
    say "PIP: I'm still training! Earn a couple more badges and come back.\pI'll have a new trick or two by then!"
  end
end

# ---------------------------------------------------------------- Fernwick: trade / Brindle: catch-count aide
script p4a_fern_trader
  faceplayer
  if flag p4a_traded_rookidee
    say "TRADER: How's Corvo doing? In faraway Galar, Rookidee grow into giant armored ravens!"
  else
    say "TRADER: I'm building a pigeon loft. Well, a Pidgey loft! But I've only got this Rookidee."
    if not quest p4a_feathered_trade started
      quest p4a_feathered_trade start
    end
    ask "Would you trade me a Pidgey for my Rookidee, Corvo?"
    if result yes
      trade pidgey rookidee 8 nick=Corvo ot=TALBOT
      if result 1
        setflag p4a_traded_rookidee
        quest p4a_feathered_trade done
        say "TRADER: Wonderful! Corvo is feisty and loves to peck. Treat it well!"
      else
        say "TRADER: No Pidgey with you? They flutter all over Route 1 in the morning and daytime."
      end
    else
      say "TRADER: A pity. Corvo's a fine bird. Come back if you change your mind!"
    end
  end
end

script p4a_dex_aide_1
  faceplayer
  if flag p4a_dex_reward_1
    say "AIDE: Keep filling that Pokédex! My colleagues in Port Tidewater and Voltara City reward bigger collections."
  elif caughtcount >= 10
    say "AIDE: Let me see your Pokédex... Ten kinds caught already? Prof. Linden will be thrilled!"
    say "AIDE: He asked me to hand these out to promising trainers. Here you go!"
    giveitem greatball 5
    giveitem expcandys 3
    setflag p4a_dex_reward_1
    say "AIDE: Exp. Candy gives a Pokémon experience straight away. Great for catching up new teammates!"
  else
    say "AIDE: I'm Prof. Linden's aide. I'm studying how trainers fill their Pokédex.\pCatch ten different kinds of Pokémon and show me. There's a reward in it for you!"
  end
end

# ---------------------------------------------------------------- Fernwick east: Petal Path
script p4a_fw_path_sign
  say "PETAL PATH - east\nA meadow of flowers and berries."
end

script p4a_pp_sign
  say "PETAL PATH\pBerry Cottage - north\nFishing pond - south"
end

script p4a_berry_grower
  faceplayer
  if not flag p4a_berry_gift
    say "BERRY GROWER: Welcome, welcome! I grow berries for the whole valley.\pTake these. An Oran Berry heals a Pokémon in a pinch, all by itself!"
    giveitem oranberry 3
    giveitem pechaberry 2
    setflag p4a_berry_gift
    say "BERRY GROWER: Come back whenever you earn a new badge. My bushes will have something special by then."
  elif badges >= 3 and not flag p4a_berry_b3
    say "BERRY GROWER: Three badges! Here, my Lum Berries finally ripened. They cure any status problem."
    giveitem lumberry 2
    setflag p4a_berry_b3
  elif badges >= 1 and not flag p4a_berry_b1
    say "BERRY GROWER: Ah, a badge! Have some Sitrus Berries. They heal far more than an Oran."
    giveitem sitrusberry 2
    setflag p4a_berry_b1
  else
    say "BERRY GROWER: Berries grow best with patience and good dirt. Pokémon, too!"
  end
end

script p4a_honey_girl
  faceplayer
  if flag p4a_honey_shown
    say "Combee are so cute when they buzz. Grandpa says they help his berries grow!"
  elif party has combee
    say "A Combee! Can I hold it? Bzzz! Thank you, thank you!"
    say "Here, Grandpa's Honey. Spread it on a tree... I mean, just keep it!"
    giveitem honey 3
    setflag p4a_honey_shown
  else
    say "I love Combee! They buzz around the flowers outside in the morning.\pIf you catch one, will you show me?"
  end
end

# ---------------------------------------------------------------- Brindle City: Lantern Tower
script p4a_bc_tower_sign
  say "LANTERN TOWER\nA place of quiet training. Visitors welcome."
end

script p4a_lt_greeter
  faceplayer
  if trainer p4a_lt_elder_rowan
    say "The Elder told us about your battle! Even Hazel took three tries to beat him."
  else
    say "Welcome to the Lantern Tower. Our sages train their minds and their Pokémon here.\pElder Rowan waits on the top floor for trainers brave enough to climb."
  end
end

script p4a_lt_lantern
  say "A great bronze lantern, green with age. Its glass holder is empty.\pSomething bright must have rested inside it once."
end

script p4a_lt_elder
  faceplayer
  if flag p4a_lt_elder_gift
    say "ELDER ROWAN: The light shards are scattered and stolen, but light always finds its way home.\pRemember that when the darkness gets close."
  elif trainer p4a_lt_elder_rowan
    say "ELDER ROWAN: Take this. Swift's stars never miss, just as true light never strays."
    tm swift
    setflag p4a_lt_elder_gift
  else
    say "ELDER ROWAN: This lantern once held a shard of light from Mt. Prism. Long ago it was hidden away, for safety."
    say "ELDER ROWAN: Now I hear people in dark uniforms are stealing shards from the shrines. Troubling.\pBut first... show me the strength of your bond!"
    battle p4a_lt_elder_rowan
    say "ELDER ROWAN: Well done. Take this. Swift's stars never miss, just as true light never strays."
    tm swift
    setflag p4a_lt_elder_gift
    say "ELDER ROWAN: If those dark uniforms seek the light, they will go where it is strongest. Mt. Prism. Be watchful, young one."
  end
end
