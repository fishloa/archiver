import {
	fetchAdminStats,
	runAudit,
	fetchUsers,
	createUser,
	updateUser,
	deleteUser,
	addUserEmail,
	removeUserEmail
} from '$lib/server/api';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async ({ locals }) => {
	const email = locals.userEmail;
	const [stats, users] = await Promise.all([
		fetchAdminStats(),
		email ? fetchUsers(email).catch(() => []) : Promise.resolve([])
	]);
	return { stats, users };
};

export const actions: Actions = {
	audit: async ({ locals }) => {
		const result = await runAudit(locals.userEmail);
		return { fixed: result.fixed };
	},
	createUser: async ({ request, locals }) => {
		const data = await request.formData();
		const displayName = data.get('displayName') as string;
		const role = data.get('role') as string;
		const emailsRaw = data.get('emails') as string;
		const emails = emailsRaw
			? emailsRaw
					.split(',')
					.map((e) => e.trim())
					.filter(Boolean)
			: [];
		await createUser(locals.userEmail!, { displayName, role, emails });
		return { userAction: 'created' };
	},
	updateUser: async ({ request, locals }) => {
		const data = await request.formData();
		const id = Number(data.get('id'));
		const displayName = data.get('displayName') as string;
		const role = data.get('role') as string;
		await updateUser(locals.userEmail!, id, { displayName, role });
		return { userAction: 'updated' };
	},
	deleteUser: async ({ request, locals }) => {
		const data = await request.formData();
		const id = Number(data.get('id'));
		await deleteUser(locals.userEmail!, id);
		return { userAction: 'deleted' };
	},
	addEmail: async ({ request, locals }) => {
		const data = await request.formData();
		const userId = Number(data.get('userId'));
		const email = data.get('email') as string;
		await addUserEmail(locals.userEmail!, userId, email);
		return { userAction: 'emailAdded' };
	},
	removeEmail: async ({ request, locals }) => {
		const data = await request.formData();
		const userId = Number(data.get('userId'));
		const emailId = Number(data.get('emailId'));
		await removeUserEmail(locals.userEmail!, userId, emailId);
		return { userAction: 'emailRemoved' };
	}
};
