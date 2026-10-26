# Route 2 rival battle and the Whisperwood shrine (Part 1).

script p1_route2_rival
  face rival down
  emote rival !
  say "{rival}: Hey, {player}! You got through Whisperwood too?\pThose Eclipse creeps tried to stop me. Didn't work!"
  if flag starter_grass
    battle rival_r2_cyndaquil canlose
  elif flag starter_fire
    battle rival_r2_totodile canlose
  else
    battle rival_r2_chikorita canlose
  end
  if result win
    say "{rival}: Grr... Fine! I'll beat Hazel first, then come back for you!"
  else
    say "{rival}: Ha! See you at the Gym, slowpoke!"
  end
  heal
  setflag p1_rival_route2
  move rival up
  hide rival
end

script p1_ww_grunt_boss
  face ww_grunt_boss down
  if not flag p1_ww_grunt_talked
    say "GRUNT: Hey! You're not supposed to be here!\pWe're on important Team Eclipse business. The shard of light is ours!"
    setflag p1_ww_grunt_talked
  else
    say "GRUNT: You again? Fine, I'll teach you a lesson!"
  end
  battle p1_ww_grunt2
  say "GRUNT: Tch! Doesn't matter. We already sent the shard to the boss.\pTeam Eclipse, retreat!"
  setflag p1_ww_grunts_fled
  fadeout
  hide ww_grunt_boss
  hide ww_grunt1
  fadein
  quest p1_light_shards start
  quest p1_light_shards next
end

script p1_ww_sage
  faceplayer
  if flag p1_ww_sage_gift
    say "SAGE: Mt. Prism's light shines through every shrine in Tessera.\pIf Team Eclipse gathers enough shards... I shudder to think."
  elif flag p1_ww_grunts_fled
    say "SAGE: You drove them off! Alas, the light shard is gone.\pStill, the forest thanks you. Please, take this."
    tm flash
    setflag p1_ww_sage_gift
    say "SAGE: Team Eclipse spoke of a 'long night.' Be careful on your journey, child."
  else
    say "SAGE: Those people in black took the shrine's light shard!\pThey went north, toward the forest exit. Please, stop them!"
  end
end
