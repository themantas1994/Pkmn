# Fernwick Town (Part 1): Exp. Share gift, lost Poké Doll side quest.

script p1_fern_aide_call
  emote aide !
  say "AIDE: {player}! Over here! Prof. Linden asked me to catch you."
  call p1_fern_aide_gift
end

script p1_fern_aide
  faceplayer
  if not flag p1_got_expshare
    call p1_fern_aide_gift
  elif badges >= 1
    say "AIDE: A badge already? The Professor will be thrilled.\pRemember: the Exp. Share can be switched off in your bag if you want a challenge."
  else
    say "AIDE: With the Exp. Share, your whole team learns from every battle.\pPokémon that sit on the bench still grow strong!"
  end
end

script p1_fern_aide_gift
  say "AIDE: This is the Exp. Share. It's still a prototype, so treat it kindly!"
  giveitem expshare
  setflag p1_got_expshare
  say "AIDE: The Pokémon Center is the building with the red roof.\pHeal there whenever your team gets tired. It's free!"
end

script p1_fern_granny
  faceplayer
  if flag p1_ww_grunts_fled
    say "You chased those Eclipse ruffians out of Whisperwood?\pThe forest spirits will remember your kindness, dear."
  else
    say "Whisperwood has an old shrine deep inside.\pThey say it keeps a sliver of Mt. Prism's light. Be respectful in there."
  end
end

script p1_fern_doll_girl
  faceplayer
  if quest p1_lost_doll done
    say "Pippi the Poké Doll is home again! Thank you, {player}!"
  elif item pokedoll
    say "That's... that's my Poké Doll! You found it!"
    takeitem pokedoll
    say "I want you to have this. Mom says it's lucky!"
    giveitem amuletcoin
    quest p1_lost_doll done
    say "An Amulet Coin doubles the prize money you win if your Pokémon holds it!"
  elif quest p1_lost_doll started
    say "I dropped it near a big tree in Whisperwood.\pPlease find my Poké Doll!"
  else
    say "Waaah! I lost my Poké Doll in Whisperwood!\pA Caterpie startled me and I ran all the way home..."
    quest p1_lost_doll start
    say "Will you look for it? It's pink and fluffy!"
  end
end

script p1_fern_doll_mom
  faceplayer
  if quest p1_lost_doll done
    say "Thank you for helping my daughter. She hasn't stopped smiling!"
  else
    say "My daughter has been crying all day over that doll.\pI'd go look, but the forest is full of bug Pokémon..."
  end
end
