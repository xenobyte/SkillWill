package com.sinnerschrader.skillwill.domain.skills;

import org.json.JSONObject;
import org.springframework.data.annotation.Id;

/**
 * A skill owned by a person
 * includes name, skill level and will level
 *
 * @author torree
 */
public class UserSkill {

  @Id
  private String name;
  private int skillLevel;
  private int willLevel;
  private boolean hidden;
  private boolean mentor;

  public UserSkill(String name, int skillLevel, int willLevel, boolean hidden, boolean mentor) {
    this.name = name;
    this.skillLevel = skillLevel;
    this.willLevel = willLevel;
    this.hidden = hidden;
    this.mentor = mentor;
  }

  public String getName() {
    return this.name;
  }

  public int getSkillLevel() {
    return skillLevel;
  }

  public void setSkillLevel(int skillLevel) {
    this.skillLevel = skillLevel;
  }

  public int getWillLevel() {
    return willLevel;
  }

  public void setWillLevel(int willLevel) {
    this.willLevel = willLevel;
  }

  public void setHidden(boolean hidden) {
    this.hidden = hidden;
  }

  public boolean isHidden() {
    return this.hidden;
  }

  public void setMentor(boolean mentor) {
    this.mentor = mentor;
  }

  public boolean isMentor() {
    return this.mentor;
  }

  public JSONObject toJSON() {
    JSONObject o = new JSONObject();
    o.put("name", this.name);
    o.put("skillLevel", this.skillLevel);
    o.put("willLevel", this.willLevel);
    o.put("mentor", this.mentor);
    return o;
  }

}