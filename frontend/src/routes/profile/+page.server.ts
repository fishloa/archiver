import { redirect } from "@sveltejs/kit";
import type { RequestEvent } from "@sveltejs/kit";
import {
  fetchProfile,
  updateProfile,
  addProfileEmail,
  removeProfileEmail,
} from "$lib/server/api";

export async function load({ locals }: { locals: App.Locals }) {
  if (!locals.userEmail) {
    throw redirect(302, "/signin");
  }
  const profile = await fetchProfile(locals.userEmail);
  return { profile };
}

export const actions = {
  updateName: async ({ request, locals }: RequestEvent) => {
    if (!locals.userEmail) throw redirect(302, "/signin");
    const form = await request.formData();
    const displayName = form.get("displayName") as string;
    await updateProfile(locals.userEmail, {
      displayName: displayName?.trim() || "",
    });
    return { success: true };
  },
  addEmail: async ({ request, locals }: RequestEvent) => {
    if (!locals.userEmail) throw redirect(302, "/signin");
    const form = await request.formData();
    const email = form.get("email") as string;
    if (!email?.trim()) return { error: "Email is required" };
    try {
      await addProfileEmail(locals.userEmail, email.trim());
      return { success: true };
    } catch (e: unknown) {
      return { error: e instanceof Error ? e.message : "Unknown error" };
    }
  },
  removeEmail: async ({ request, locals }: RequestEvent) => {
    if (!locals.userEmail) throw redirect(302, "/signin");
    const form = await request.formData();
    const emailId = Number(form.get("emailId"));
    try {
      await removeProfileEmail(locals.userEmail, emailId);
      return { success: true };
    } catch (e: unknown) {
      return { error: e instanceof Error ? e.message : "Unknown error" };
    }
  },
};
